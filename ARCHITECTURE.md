# CountryInfo — System Design & Operations

Answers to the evaluation criteria, mapped to the actual code in this repository.

---

## 1. System design, integration patterns & component interactions

### High-level flow

```
Client ──HTTP/JSON──► CountryInfoController ──► CountryInfoService ──► CountryInfoSoapClient ──SOAP/XML──► oorsprong CountryInfoService
                                                      │                        │
                                                      │                        └── SoapEnvelopes (request)  + CountryInfoResponseMapper (response)
                                                      ▼
                                              CountryInfoRepository ──JPA──► MySQL
```

`POST /api/v1/countries {"name":"tanzania"}` triggers the orchestration required by the brief:

1. `CountryInfoService.toSentenceCase()` normalises `tanzania` → `Tanzania`.
2. `CountryISOCode(sCountryName)` → `TZ`.
3. `FullCountryInfo(sCountryISOCode)` → full record incl. languages.
4. Persisted as `CountryInfo` + `Language`, returned as a DTO.

### Integration pattern: Anti-Corruption Layer

The SOAP integration is an **anti-corruption layer**. The upstream XML contract (`sISOCode`, `tLanguage`, `FullCountryInfoResult`) never leaks past the `client` package — it is mapped into the `FullCountryInfo` record, and the JPA entities/DTOs are modelled for *our* domain. If the provider changes or is swapped for a REST source, only `client/` changes.

Within that layer, responsibilities are split so each piece is independently testable:

| Component | Responsibility |
|---|---|
| `SoapEnvelopes` | Builds request envelopes; XML-escapes all caller input |
| `CountryInfoResponseMapper` | Parses/maps XML; translates upstream errors to domain exceptions |
| `CountryInfoSoapClient` | Transport + resilience policy (timeouts, retries, breaker, cache) |

### Key decisions & trade-offs

| Decision | Justification | Trade-off accepted |
|---|---|---|
| **Hand-built SOAP envelopes over JAX-WS/`wsdl2java`** | Only 2 of 21 operations are used. Avoids generating ~40 classes and a build-time WSDL fetch that breaks CI when the public service is down. | Manual mapping code. Mitigated by `CountryInfoSoapClientParsingTest`, which pins parsing against WSDL-shaped payloads. |
| **Synchronous orchestration** (not queued) | The client needs the stored record in the response; a 202 + polling would complicate the contract for a sub-second call. | Request latency is bound to upstream latency. Capped by the 3s/5s timeouts and absorbed by caching. |
| **`RestClient` + JDK `HttpClient`** | Connection pooling and per-request read timeouts without extra dependencies. | — |
| **Upsert by ISO code** | `POST` of the same country refreshes rather than duplicating; makes the endpoint idempotent on the natural key. | A `POST` can mutate an existing row. Deliberate, and `isoCode` carries a unique constraint. |
| **MySQL for persistence** | Required by the brief; relational model fits the 1-to-many country→language. | — |
| **DTOs separate from entities** | Prevents lazy-loading leaks and decouples the API contract from the schema. | Extra mapping code. |

---

## 2. Designing for high load

- **Stateless services.** No HTTP session, no in-request server state; the only mutable state is the DB and a local cache. Any replica can serve any request, which is what makes horizontal scaling safe.
- **Horizontal scaling.** `k8s/20-app.yaml` runs `replicas: 2` behind a `HorizontalPodAutoscaler` (2→10 pods at 70% CPU), fronted by a `ClusterIP` Service (L4 load balancing) and an Ingress.
- **Caching.** `@Cacheable("isoCodes")` and `@Cacheable("fullCountryInfo")` on the SOAP client. Country data is effectively static, so this removes almost all upstream traffic on repeat lookups — the single most effective protection for both latency and the third-party rate limit.
- **Connection pooling.** HikariCP, `DB_POOL_SIZE` (default 10) with a 5s connection timeout so a DB stall surfaces fast instead of exhausting threads.
- **`open-in-view: false`.** Connections are released at the end of the transaction rather than being held for view rendering — essential for pool throughput under load.
- **Graceful shutdown.** `server.shutdown: graceful` + 20s drain + a `preStop` sleep, so scale-in and rolling deploys don't drop in-flight requests.
- **PodDisruptionBudget** keeps ≥1 replica during node drains.

**Honest limitation:** the cache is Spring's default in-memory `ConcurrentMapCache` — per-pod, unbounded, no TTL. Fine for ~250 countries, but for genuine high load it should move to **Redis** (shared across replicas, with TTL + eviction). That is a one-line dependency change plus config, as the code only depends on the `@Cacheable` abstraction. Similarly, no queue is used because the workload has no fire-and-forget path; if bulk import were added, I'd publish country names to a queue and consume them with a bounded worker pool to protect the upstream service.

---

## 3. Graceful failure handling

All in `CountryInfoSoapClient.execute()`:

- **Timeouts** — 3s connect / 5s read (`SoapProperties`), so a hung upstream can never pin a request thread indefinitely.
- **Retries with exponential backoff** — 3 attempts starting at 200ms, doubling. Applied only to *retryable* faults.
- **Error classification** — a `4xx` means our request is at fault, so it is **not** retried and does not trip the breaker; timeouts, `5xx` and malformed payloads (`MalformedSoapResponseException`) are retried. Retrying a deterministic failure just multiplies load during an incident.
- **Circuit breaker** — `SimpleCircuitBreaker` (CLOSED → OPEN → HALF_OPEN), opening after 5 consecutive failures for 30s. While OPEN, calls fail fast with a counter increment instead of queuing threads against a dead dependency.
- **Fallback** — the API degrades to `503 Service Unavailable` with a retry-oriented message, while **all previously persisted data stays fully readable** via `GET`/`PUT`/`DELETE`. Read endpoints have no upstream dependency, so an outage degrades only the ingest path.
- **Interrupt-safe** — the retry sleep restores the interrupt flag rather than swallowing it.

A hand-rolled breaker was chosen over Resilience4j to keep the dependency surface minimal for a well-understood ~50-line policy; it is thread-safe (atomics) and unit-testable via an injected `Clock`. At greater complexity (bulkheads, rate limiters, metrics bindings) Resilience4j would be the right call.

---

## 4. Logging, metrics & monitoring

**Tracing** — `CorrelationIdFilter` accepts or generates an `X-Correlation-Id`, puts it in the **SLF4J MDC**, echoes it on the response, and includes it in every error body. One ID ties together the access log, service logs, SOAP logs and the client's error report.

**Structured logs** — key=value pairs, not prose, so they are greppable and parseable:

```
http_request method=POST path=/api/v1/countries status=201 durationMs=412
Resolved ISO code country='Tanzania' iso='TZ'
SOAP call failed operation=CountryISOCode attempt=2/3 error=...
```
Actuator noise is excluded from access logs. Full SOAP request/response payloads log at `DEBUG` (`SOAP_LOG_LEVEL=DEBUG`) — kept off by default because they are verbose.

**Metrics** — Micrometer + `/actuator/prometheus`:
- `soap.client.requests` — timer tagged `operation` + `outcome`, giving latency percentiles and error rate per SOAP operation
- `soap.client.rejected` — counter of circuit-breaker rejections
- plus JVM, HikariCP, and `http.server.requests` out of the box

Pods carry `prometheus.io/scrape` annotations for zero-config discovery.

**Log levels are meaningful:** `INFO` business events, `WARN` retryable/degraded, `ERROR` only genuine failures. Expected 4xx log at `INFO` so `ERROR` stays alert-worthy.

---

## 5. Separation of concerns (MVC)

| Layer | Package | Rule |
|---|---|---|
| Controller | `controller` | HTTP only: routing, validation trigger, status codes. No business logic. |
| Service | `service` | Orchestration, normalisation, transactions. No HTTP or XML types. |
| Repository | `repository` | Spring Data JPA. |
| Model | `model` | `CountryInfo`, `Language` JPA entities. |
| DTO | `dto` | Request/response contracts, validated with Jakarta Validation. |
| Client | `client` | External integration (ACL). |
| Config / Exception | `config`, `exception` | Cross-cutting concerns. |

Dependencies point strictly inward and the controller is a thin ~55-line class. Notably, the service **does not hold the transaction open across the SOAP calls** — it uses `TransactionTemplate` to open a transaction only for the write, so a slow third party never holds a DB connection.

---

## 6. Error handling & HTTP status codes

`GlobalExceptionHandler` (`@RestControllerAdvice`) returns **RFC 7807 `ProblemDetail`** for every path, enriched with the correlation ID.

| Condition | Status |
|---|---|
| Unknown country name upstream | `404` |
| Unknown local id | `404` |
| Unknown URL / wrong HTTP method | `404` / `405` |
| Validation failure, malformed JSON, type mismatch | `400` (field-level messages) |
| Wrong `Content-Type` / `Accept` | `415` / `406` |
| Constraint violation / concurrent modification | `409` |
| Upstream SOAP failure or open circuit | `503` |
| Anything else | `500` with a correlation reference only |

It extends `ResponseEntityExceptionHandler` specifically so Spring MVC's built-in exceptions **keep their correct status codes** instead of being swallowed by the catch-all `500` handler — a common bug in naive `@RestControllerAdvice` implementations. Internal details (stack traces, SQL, upstream XML) are never returned; clients get an actionable message plus a correlation ID to quote in a support request.

---

## 7. Production-ready deployment

- **Containerisation** — multi-stage `Dockerfile`: Maven build stage with a cached dependency layer, slim `eclipse-temurin:21-jre-alpine` runtime, **non-root numeric UID 10001** (so `runAsNonRoot` can be enforced), container-aware heap (`MaxRAMPercentage=75`), exec-form entrypoint so PID 1 receives `SIGTERM`, plus a `HEALTHCHECK`.
- **Configuration management** — 12-factor: every setting is an env var with a sane default. In Kubernetes, non-secrets come from a `ConfigMap` and DB credentials from a `Secret`. No credentials are baked into the image.
- **Health checks** — `startupProbe` (tolerates slow JVM start without a lax liveness probe), `livenessProbe` (restart on deadlock), `readinessProbe` (drop from the load balancer when the DB is unreachable, without killing the pod). Spring Boot's liveness/readiness probe groups are enabled.
- **Scalability support** — HPA, PDB, rolling updates with `maxUnavailable: 0`, resource requests/limits.
- **Security** — `allowPrivilegeEscalation: false`, non-root, secrets separated, XXE-hardened XML parsing, XML-escaped SOAP input.

Local orchestration is provided by `docker-compose.yml` (MySQL with a persistent volume, health-gated startup, loopback-bound DB port).

---

## 8. Running & testing the application

### A. Docker Compose (fastest)

```sh
docker compose up --build
```
App on `http://localhost:8080`. MySQL is published on **127.0.0.1:3307** to avoid clashing with a local install (override with `MYSQL_HOST_PORT`).

### B. Locally with Maven

```sh
export DB_URL='jdbc:mysql://localhost:3306/countryinfo?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export DB_USERNAME=root DB_PASSWORD=yourpassword
./mvnw spring-boot:run
```

### C. Tests

```sh
./mvnw test
```
Covers sentence-case normalisation, SOAP response parsing (ISO code, full info with languages, "not found" sentinel, SOAP fault), error-handling status codes, and context load.

### D. Manual API testing

```sh
# Create (SOAP orchestration: name -> ISO -> full info -> DB)
curl -i -X POST http://localhost:8080/api/v1/countries \
  -H 'Content-Type: application/json' -d '{"name":"tanzania"}'

curl http://localhost:8080/api/v1/countries          # list all
curl http://localhost:8080/api/v1/countries/1        # by id
curl -X PUT http://localhost:8080/api/v1/countries/1 \
  -H 'Content-Type: application/json' -d '{"name":"Tanzania","capitalCity":"Dodoma"}'
curl -X DELETE http://localhost:8080/api/v1/countries/1   # 204
```

Error paths worth demonstrating:
```sh
curl -i -X POST http://localhost:8080/api/v1/countries \
  -H 'Content-Type: application/json' -d '{"name":"Atlantis"}'   # 404
curl -i -X POST http://localhost:8080/api/v1/countries \
  -H 'Content-Type: application/json' -d '{"name":""}'           # 400
curl -i http://localhost:8080/api/v1/countries/99999             # 404
curl -i http://localhost:8080/api/v1/does-not-exist              # 404
curl -i -X PATCH http://localhost:8080/api/v1/countries/1        # 405
```

### E. Kubernetes

```sh
./k8s/deploy.sh          # or: kubectl apply -f k8s/
kubectl -n countryinfo get pods -w
kubectl -n countryinfo port-forward svc/countryinfo 8080:80
```

### F. Observability endpoints

```sh
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/actuator/prometheus | grep soap_client
```

---

## 9. Known limitations / next steps

Being explicit about what I would do with more than 3 hours:

1. **Replace the in-memory cache with Redis** (shared, TTL, eviction) — the main blocker to true horizontal scale of the cache layer.
2. **Flyway migrations** with `ddl-auto: validate`; `update` is convenient but unsafe for production schema changes.
3. **Authentication/authorisation** (Spring Security + OAuth2) — the API is currently open.
4. **Pagination on `GET /api/v1/countries`** — fine at ~250 rows, not at scale.
5. **Distributed tracing** via Micrometer Tracing/OpenTelemetry; the correlation ID is a deliberate lightweight stand-in.
6. **WireMock-based integration tests** for the SOAP client's retry/circuit-breaker behaviour, which is currently covered at the parsing level only.
