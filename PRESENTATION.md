# Case Study Submission – Integrations and Microservices Engineer

**Country Information Service — SOAP ↔ REST Integration Microservice**

Presenter: Dennis Simiyu · Spring Boot 3 / Java 21 · MySQL · Docker · Kubernetes

> Speaker notes are in blockquotes. Deep detail lives in `ARCHITECTURE.md`; this deck is the narrative.

---

## Slide 1 — The brief in one sentence

Accept a country name over REST, enrich it through a **third-party SOAP service**, persist it to **MySQL**, and expose it as a **production-grade CRUD API** on **Kubernetes**.

| Requirement | Status |
|---|---|
| Spring Boot + Web + JPA + MySQL | ✅ |
| POST endpoint, name → sentence case | ✅ |
| SOAP `CountryISOCode` → ISO code | ✅ |
| SOAP `FullCountryInfo` → full record | ✅ |
| `CountryInfo` + `Language` models | ✅ |
| CRUD REST endpoints | ✅ |
| K8s deployment scripts + guides | ✅ |

> The functional brief was the easy half. The grading criteria — resilience, observability, scalability — are where the engineering actually is, so that's where this deck spends its time.

---

## Slide 2 — The request flow

```
POST /api/v1/countries  {"name": "tanzania"}
        │
        ▼
CountryInfoController ── validation, HTTP concerns only
        │
        ▼
CountryInfoService ───── "tanzania" → "Tanzania"   (sentence case)
        │
        ├──► CountryInfoSoapClient ──SOAP──► CountryISOCode(sCountryName)    → "TZ"
        │
        ├──► CountryInfoSoapClient ──SOAP──► FullCountryInfo(sCountryISOCode) → full record
        │
        ▼
CountryInfoRepository ──JPA──► MySQL      (CountryInfo 1──N Language)
        │
        ▼
201 Created + Location header + JSON DTO
```

> Two chained SOAP calls behind one REST call. The output of call one is the input to call two — which means two chances to fail, and that drove most of the design.

---

## Slide 3 — Integration pattern: Anti-Corruption Layer

**The upstream contract is ugly** — `sISOCode`, `tLanguage`, `FullCountryInfoResult`, and "not found" returned as a *success string*, not a fault.

**None of it escapes the `client` package.**

| Component | Single responsibility |
|---|---|
| `SoapEnvelopes` | Build request XML; escape all caller input |
| `CountryInfoResponseMapper` | Parse/map XML; translate upstream quirks → domain exceptions |
| `CountryInfoSoapClient` | Transport + resilience policy only |

Everything downstream sees the clean `FullCountryInfo` record.

**Why it matters:** swap the provider for a REST API tomorrow and *only* `client/` changes. Controller, service, entities and tests are untouched.

> This three-way split also made the tricky part — XML parsing — unit-testable with zero network access.

---

## Slide 4 — Key decisions & trade-offs

| Decision | Why | Trade-off I accepted |
|---|---|---|
| **Hand-built envelopes**, not `wsdl2java` | 2 of 21 operations used; codegen adds ~40 classes **and a build-time WSDL fetch that breaks CI whenever the public service is down** | Manual mapping — pinned by parsing tests against WSDL-shaped payloads |
| **Synchronous** orchestration | Caller needs the saved record back; 202 + polling complicates the contract for a sub-second call | Latency tied to upstream — capped by timeouts, absorbed by cache |
| **Upsert by ISO code** | Re-POSTing a country refreshes instead of duplicating → idempotent on the natural key | A POST can mutate an existing row (deliberate; unique constraint on `isoCode`) |
| **Hand-rolled circuit breaker** | ~50 lines, thread-safe, `Clock`-injected for tests; avoids a dependency for a well-understood policy | Would adopt Resilience4j once bulkheads/rate limiters are needed |
| **DTOs ≠ entities** | No lazy-loading leaks; API contract decoupled from schema | Extra mapping code |

> Every one of these is a defensible choice with a cost. I'd rather name the cost than pretend it isn't there.

---

## Slide 5 — Designing for high load

**Stateless by construction** → no session, no server-side request state. Any replica serves any request. *This is the precondition for everything else.*

| Lever | Implementation |
|---|---|
| Horizontal scaling | HPA **2 → 10 pods** at 70% CPU |
| Load balancing | ClusterIP Service (L4) + nginx Ingress (L7) |
| Caching | `@Cacheable` on **both** SOAP calls — country data is effectively static |
| DB throughput | HikariCP pool + **`open-in-view: false`** (connection released at transaction end) |
| Zero-drop scaling | Graceful shutdown, 20s drain, `preStop` hook, `maxUnavailable: 0`, PDB |

**Biggest win:** the cache. Country data never changes, so repeat traffic never touches the third party at all.

> ⚠️ **Honest limitation:** that cache is Spring's default `ConcurrentMapCache` — per-pod, no TTL, no eviction. Correct for ~250 countries; **not** a real high-load cache. Redis is the fix, and because the code only depends on the `@Cacheable` abstraction it's a dependency + config change, not a rewrite.

---

## Slide 6 — Graceful failure handling

The service depends on a **free public SOAP endpoint**. It *will* fail. Four layers:

```
1. TIMEOUTS          3s connect / 5s read      → a hung upstream never pins a thread
2. RETRIES           3 attempts, 200ms ×2      → rides out transient blips
3. CIRCUIT BREAKER   5 failures → open 30s     → fail fast, stop hammering a dead service
4. FALLBACK          503 + retry-friendly msg  → degrade, don't collapse
```

**The subtle bit — error classification:**

| Failure | Retry? | Trip breaker? | Why |
|---|---|---|---|
| Timeout / 5xx / malformed XML | ✅ | ✅ | Transient — retrying can win |
| **4xx** | ❌ | ❌ | **Our request is wrong. Retrying just multiplies load during an incident.** |
| Unknown country | ❌ | ❌ | A valid business answer → `404`, not an error |

**Blast radius is contained:** when the SOAP service is down, only *ingest* (POST) degrades. Every `GET`/`PUT`/`DELETE` keeps serving persisted data normally.

> Naive retry-everything logic is actively harmful — it turns a partial outage into a self-inflicted DDoS. That distinction is the point of this slide.

---

## Slide 7 — Observability: logs, metrics, tracing

**One ID ties it all together.** `CorrelationIdFilter` accepts or generates `X-Correlation-Id` → MDC → every log line → response header → **and into the error body**. A user quotes the ID from their error; I grep one string and see the whole request.

**Structured, greppable logs** — key=value, never prose:
```
http_request method=POST path=/api/v1/countries status=201 durationMs=412
Resolved ISO code country='Tanzania' iso='TZ'
SOAP call failed operation=CountryISOCode attempt=2/3 error=...
SOAP call exhausted 3 attempts operation=CountryISOCode circuitState=OPEN
```

**Metrics** via Micrometer → `/actuator/prometheus` (pods carry scrape annotations):
- `soap.client.requests` — timer tagged `operation` + `outcome` → **per-operation latency percentiles and error rate**
- `soap.client.rejected` — circuit-breaker rejections
- plus JVM, HikariCP, `http.server.requests` free

**Disciplined levels:** `INFO` business events · `WARN` degraded-but-recovering · `ERROR` genuine failure. Expected 4xx log at `INFO` — **so that `ERROR` stays alert-worthy.**

> Full SOAP payload logging sits behind `SOAP_LOG_LEVEL=DEBUG` — flip it live via the ConfigMap when troubleshooting, off by default because it's noisy.

---

## Slide 8 — Separation of concerns (MVC)

```
controller → service → repository → model        (dependencies point strictly inward)
                ↓
              client  (external integration)
```

| Layer | Rule enforced |
|---|---|
| `controller` | HTTP only — **~55 lines, zero business logic** |
| `service` | Orchestration, normalisation, transactions — no HTTP or XML types |
| `repository` | Spring Data JPA |
| `model` / `dto` | Entities vs. API contracts, kept separate |
| `client` | Anti-corruption layer |

**One detail I'm proud of:** the service uses `TransactionTemplate` to open a transaction **only around the DB write** — *not* across the SOAP calls.

> If you wrap the whole method in `@Transactional`, a slow third party holds a DB connection hostage for the entire call. Under load that exhausts the pool and takes down endpoints that never needed the SOAP service. Easy mistake, nasty outage.

---

## Slide 9 — Error handling & status codes

Every response — success or failure — is **RFC 7807 `ProblemDetail`**, enriched with the correlation ID.

| Condition | Status |
|---|---|
| Unknown country upstream / unknown local id / unknown URL | `404` |
| Wrong HTTP method | `405` |
| Validation failure, malformed JSON, type mismatch | `400` *(field-level messages)* |
| Wrong `Content-Type` / `Accept` | `415` / `406` |
| Constraint violation / concurrent modification | `409` |
| SOAP failure or open circuit | `503` |
| Anything unexpected | `500` + correlation ID **only** |

**Why it extends `ResponseEntityExceptionHandler`:** a plain `@RestControllerAdvice` with a catch-all `Exception` handler swallows Spring MVC's own exceptions and turns a 404 or 405 into a **500**. Extending it preserves correct semantics.

> This was a real bug in my first pass — 404s were coming back as 500s. Worth calling out because it's a very common one.

Internal details — stack traces, SQL, upstream XML — are **never** returned.

---

## Slide 10 — Production readiness

**Container** — multi-stage build; cached dependency layer; slim `temurin:21-jre-alpine`; **non-root numeric UID 10001** (so K8s `runAsNonRoot` can verify it); `MaxRAMPercentage=75` for container-aware heap; **exec-form entrypoint so PID 1 receives `SIGTERM`**; `HEALTHCHECK`.

**Config** — 12-factor. Every setting an env var with a sane default. ConfigMap for settings, **Secret** for credentials. Nothing baked into the image.

**Three distinct probes, three distinct jobs:**

| Probe | Job |
|---|---|
| `startupProbe` | Absorb slow JVM start — **without weakening liveness** |
| `livenessProbe` | Restart genuinely wedged pods |
| `readinessProbe` | Pull from the load balancer when DB is unreachable — **without killing the pod** |

**Scale & safety** — HPA · PDB · rolling update `maxUnavailable: 0` · resource requests/limits · `allowPrivilegeEscalation: false` · **XXE-hardened XML parsing** · XML-escaped SOAP input.

> Using one probe for all three jobs is the classic K8s anti-pattern: a slow-starting app gets killed mid-boot, and a brief DB blip restarts every pod simultaneously.

---

## Slide 11 — Running & testing it

**Fastest path — one command:**
```sh
docker compose up --build        # app :8080, MySQL :3307 (avoids local MySQL clash)
```

**Tests:**
```sh
./mvnw test   # sentence case · SOAP parsing (ISO, full info, "not found", SOAP fault) · status codes
```

**The happy path:**
```sh
curl -i -X POST localhost:8080/api/v1/countries \
  -H 'Content-Type: application/json' -d '{"name":"tanzania"}'   # 201 + Location

curl localhost:8080/api/v1/countries      # list        curl localhost:8080/api/v1/countries/1   # by id
curl -X PUT ... /1                        # update      curl -X DELETE ... /1                    # 204
```

**The paths that prove the error handling:**
```sh
-d '{"name":"Atlantis"}'   → 404      -d '{"name":""}'   → 400
GET /countries/99999       → 404      GET /does-not-exist → 404      PATCH /countries/1 → 405
```

**Kubernetes:**
```sh
./k8s/deploy.sh && kubectl -n countryinfo get pods -w
kubectl -n countryinfo port-forward svc/countryinfo 8080:80
```

> Full command list, troubleshooting guide and observability endpoints: `ARCHITECTURE.md` + `README.md`.

---

## Slide 12 — What I'd do next

I'd rather state the gaps than have them found:

1. **Redis cache** — replaces per-pod `ConcurrentMapCache`; the one real blocker to true horizontal cache scale
2. **Flyway + `ddl-auto: validate`** — `update` is convenient, unsafe for production schema change
3. **Spring Security / OAuth2** — the API is currently open
4. **Pagination** on `GET /countries` — fine at 250 rows, not at scale
5. **OpenTelemetry tracing** — the correlation ID is a deliberate lightweight stand-in
6. **WireMock integration tests** for retry/breaker behaviour — covered at parsing level only today

---

## Slide 13 — Closing

**What this submission demonstrates:**

- A clean **integration pattern** (ACL) that isolates an awkward third-party contract
- **Resilience that distinguishes** retryable from non-retryable failure
- **Observability** designed for the 3am debugging session, not for a checklist
- **Correct HTTP semantics** end to end
- A **container and K8s manifest set** that respects how the platform actually behaves
- **Explicit trade-offs**, including the limitations

**Repository:** `<GitHub URL>` · **Deep dive:** `ARCHITECTURE.md` · **Deploy/troubleshoot:** `README.md`

Thank you — happy to walk through any component live.
