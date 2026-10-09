# Country Info Integration Service

Spring Boot 4 / Java 21 microservice. It takes a country name over REST, calls the
[oorsprong CountryInfoService](http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso?WSDL)
SOAP API to get the ISO code and then the full country details, saves the result in MySQL,
and offers CRUD endpoints for the saved data. It ships with Docker and Kubernetes deployment files.

---

## 1. Architecture

```
Client ──HTTP/JSON──> Ingress ──> Service (LB) ──> N x countryinfo pods (stateless)
                                                       │  Controller  (REST, validation)
                                                       │  Service     (business logic, sentence case, upsert, tx)
                                                       │  SoapClient  (timeouts, retry+backoff, circuit breaker, cache)
                                                       │  Repository  (Spring Data JPA)
                                                       ├──SOAP/XML──> oorsprong CountryInfoService
                                                       └──JDBC──────> MySQL (StatefulSet + PVC)
```

| Layer | Package | Responsibility |
|---|---|---|
| Controller | `controller` | HTTP mapping, `@Valid` input, status codes (201/200/204) |
| Service | `service` | Sentence-casing, orchestration (ISO code → FullCountryInfo), upsert, transactions |
| Client | `client` | SOAP 1.1 envelopes, secure XML parsing (XXE disabled), resilience, metrics |
| Model / Repository | `model`, `repository` | `CountryInfo` 1─* `Language` JPA entities |
| Cross-cutting | `config`, `exception` | Correlation-ID filter, RFC 7807 error handling, configuration |

### Design decisions and trade-offs
* **Synchronous request/response integration.** The caller needs the stored record straight away, so the
  service calls the SOAP API inline. A queue (Kafka/RabbitMQ with 202 Accepted) would decouple the two
  better, but it makes the API harder to use. It's a reasonable next step if traffic is bulk or batch.
* **Raw SOAP envelopes with RestClient instead of JAXB/wsimport.** This needs no code generation and keeps
  dependencies small. Only two operations are used. The trade-off is that the WSDL contract isn't
  type-checked at compile time.
* **The SOAP calls run outside the DB transaction.** No JDBC connection is held while waiting on remote I/O,
  which matters for keeping the connection pool healthy under load.
* **Upsert by ISO code.** POSTing the same country twice refreshes the existing record instead of creating a duplicate (idempotent).
* **Stateless pods.** All state is in MySQL, so the app scales horizontally with an HPA and the Service load-balances between pods.
* **Caching.** Country reference data rarely changes, so ISO-code and full-info lookups are cached
  (Spring Cache). That cuts upstream calls and latency, and some data is still served if the upstream is down.
  The cache is per pod. Use Redis if you need a shared cache.
* **Resilience.** HTTP connect timeout (3s) and read timeout (5s); retries with exponential backoff on
  transient errors only (4xx is never retried); a circuit breaker that opens after N failed calls and fails
  fast with **503**. The circuit breaker is a small hand-written class rather than Resilience4j, to avoid
  library compatibility issues with Boot 4. Swapping in Resilience4j later is easy.
* **Optimistic locking** (`@Version`) protects against lost updates when several replicas write.

### Observability
* Every request gets an `X-Correlation-Id` (taken from the request or generated). It is added to the logs (MDC), the response header and error bodies.
* Each request is logged with `method`, `path`, `status` and `durationMs`. In Kubernetes the logs are JSON (ECS) through `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.
* Every SOAP call logs `SOAP request operation=… attempt=… payload=<envelope>` and
  `SOAP response operation=… status=… durationMs=… payload=<xml>` (or `SOAP call failed …` / `SOAP fault …`).
  Payloads are collapsed onto one line and cut off at 4000 characters. All lines carry the correlation ID, so
  `grep <correlationId>` shows the REST call and its SOAP calls together.
* Metrics are at `/actuator/prometheus`, including `soap_client_requests_seconds{operation,outcome}`, `soap_client_rejected_total`, `http_server_requests_seconds` and the Hikari/JVM metrics.
* Health checks: `/actuator/health/liveness` and `/actuator/health/readiness` (the readiness check includes the DB).

---

## 2. API

Base path: `/api/v1/countries`

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| POST | `/` | `{"name":"tanzania"}` | 201 + saved country | 400 validation, 404 unknown country, 503 SOAP down |
| GET | `/` | – | 200 list | |
| GET | `/{id}` | – | 200 | 404 |
| PUT | `/{id}` | `CountryUpdateRequest` | 200 | 400, 404, 409 (concurrent update) |
| DELETE | `/{id}` | – | 204 | 404 |

Errors use the RFC 7807 `application/problem+json` format:
```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Country info with id 9 not found","correlationId":"..."}
```

---

## 3. Running and testing locally

Requirements: JDK 21, Docker.

```bash
# Option A – everything in Docker
docker compose up --build

# Option B – MySQL in Docker, app from Maven
docker compose up -d mysql
./mvnw spring-boot:run

# Unit/context tests (H2 in-memory, no MySQL needed)
./mvnw test
```

Try it out:
```bash
curl -i -X POST localhost:8080/api/v1/countries -H 'Content-Type: application/json' -d '{"name":"kenya"}'
curl localhost:8080/api/v1/countries
curl localhost:8080/api/v1/countries/1
curl -i -X PUT localhost:8080/api/v1/countries/1 -H 'Content-Type: application/json' \
     -d '{"name":"Kenya","capitalCity":"Nairobi","phoneCode":"254","continentCode":"AF","currencyIsoCode":"KES","countryFlag":"http://example/ke.jpg","languages":[{"isoCode":"sw","name":"Swahili"}]}'
curl -i -X DELETE localhost:8080/api/v1/countries/1
curl -i -X POST localhost:8080/api/v1/countries -H 'Content-Type: application/json' -d '{"name":"atlantis"}'   # 404
curl localhost:8080/actuator/health
```

### SoapUI
In SoapUI choose File → New SOAP Project and paste the WSDL URL
`http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso?WSDL`.
Run `CountryISOCode` with `sCountryName=Kenya` (returns `KE`), then `FullCountryInfo` with `sCountryISOCode=KE`.
The app runs the same two calls in that order.

---

## 4. Deploying to Kubernetes

Manifests are in `k8s/`:

| File | Contents |
|---|---|
| `00-namespace-config.yaml` | Namespace, Secret (DB credentials), ConfigMap (DB URL, SOAP endpoint, timeouts, log format) |
| `10-mysql.yaml` | MySQL StatefulSet + PVC + headless Service |
| `20-app.yaml` | Deployment (2 replicas, rolling update, probes, resources, non-root), Service, HPA (2–10 @70% CPU), PDB, Ingress |
| `deploy.sh` | Build image → load into minikube/kind → apply → wait for rollout |

### Steps (minikube example)
```bash
minikube start --cpus 4 --memory 6g
minikube addons enable ingress metrics-server
# 1. Edit secrets in k8s/00-namespace-config.yaml (or create them with kubectl create secret)
# 2. Deploy
chmod +x k8s/deploy.sh && ./k8s/deploy.sh 1.0.0
# 3. Access
kubectl -n countryinfo port-forward svc/countryinfo 8080:80
curl -X POST localhost:8080/api/v1/countries -H 'Content-Type: application/json' -d '{"name":"tanzania"}'
# or with the ingress: echo "$(minikube ip) countryinfo.local" | sudo tee -a /etc/hosts
```

For a real cluster, push the image to a registry (`docker tag countryinfo:1.0.0 <registry>/countryinfo:1.0.0 && docker push ...`),
change `image:` in `20-app.yaml`, then run `kubectl apply -f k8s/`.

Upgrade: `kubectl -n countryinfo set image deploy/countryinfo countryinfo=<image>:<newtag>`. The rolling update uses maxUnavailable=0 so there is no downtime.
Rollback: `kubectl -n countryinfo rollout undo deploy/countryinfo`.
Scale manually: `kubectl -n countryinfo scale deploy/countryinfo --replicas=4` (the HPA normally does this).

---

## 5. Troubleshooting on Kubernetes

```bash
kubectl -n countryinfo get pods,svc,hpa,endpoints -o wide     # overall state
kubectl -n countryinfo describe pod <pod>                       # events: image pull, probe failures, OOMKilled
kubectl -n countryinfo logs <pod> [--previous] [-f]             # app logs (JSON); --previous after a crash
kubectl -n countryinfo logs <pod> -c wait-for-mysql             # init container stuck?
kubectl -n countryinfo get events --sort-by=.lastTimestamp
kubectl -n countryinfo exec -it <pod> -- sh                     # shell into the container
kubectl -n countryinfo port-forward <pod> 8080:8080             # bypass Service/Ingress
curl localhost:8080/actuator/health                             # component health (db, diskSpace...)
curl localhost:8080/actuator/prometheus | grep soap_client      # upstream latency/errors
kubectl top pods -n countryinfo                                 # CPU/memory (metrics-server)
```

| Symptom | Likely cause | What to do |
|---|---|---|
| `ImagePullBackOff` / `ErrImagePull` | Wrong image name or tag, image not loaded into minikube/kind, missing registry credentials | Check `describe pod`; run `minikube image load`; add `imagePullSecrets` |
| Pod stuck in `Init:0/1` | MySQL isn't ready | `kubectl -n countryinfo get pods -l app=mysql`, read the MySQL logs, check that the PVC is `Bound` |
| `CrashLoopBackOff` | Bad DB credentials or URL, schema error | `logs --previous`; check the Secret/ConfigMap (`kubectl get cm countryinfo-config -o yaml`) |
| `OOMKilled` | Memory limit too low | Raise `limits.memory` or lower `MaxRAMPercentage` |
| Readiness keeps failing, 503 at the Ingress | DB down (readiness includes the DB), app still starting | `curl /actuator/health`; check `kubectl get endpoints countryinfo` |
| POST returns 503 "service unavailable" | SOAP upstream is slow or down, the circuit breaker is open, or egress is blocked by a NetworkPolicy/proxy | Check the `soap_client_requests_seconds{outcome="error"}` metric and the logs (`SOAP call failed`); test from the pod: `kubectl exec <pod> -- wget -qO- "$SOAP_ENDPOINT?WSDL"`; check DNS/egress |
| POST returns 404 | The name isn't known to the SOAP service | Check the spelling (the app sentence-cases input, e.g. "kenya" → "Kenya") |
| 409 on PUT | Concurrent update (optimistic lock) | Retry the request with fresh data |
| HPA shows `<unknown>` | metrics-server is missing | `minikube addons enable metrics-server` |

Follow a single request across pods by searching the logs for its correlation ID:
`kubectl -n countryinfo logs -l app=countryinfo --prefix | grep <correlationId>`.
