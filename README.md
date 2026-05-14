# SMPP Observability Platform

Java full-stack technical assignment: an SMPP gateway that accepts SMS submissions synchronously,
persists them durably, processes delivery asynchronously, and exposes operational visibility through
Admin APIs, Prometheus metrics, Grafana dashboards, Jaeger traces, RabbitMQ queues, and a React admin
dashboard.

## What Is Implemented

- SMPP 3.4 server using Cloudhopper:
  - `bind_transceiver`
  - `submit_sm`
  - `enquire_link`
  - `unbind`
- Synchronous `submit_sm` accept path:
  - validates message fields
  - applies per-`system_id` throttling
  - persists accepted messages in PostgreSQL
  - returns `submit_sm_resp` quickly with a generated `message_id`
- Asynchronous processing path:
  - scheduled handoff from database state `RECEIVED` to RabbitMQ
  - RabbitMQ consumer calls a mock HTTP sender
  - successful send marks the message `PROCESSED`
  - retryable failures use a RabbitMQ TTL retry queue
  - terminal failures are marked `FAILED` and copied to a DLQ for inspection
- Observability:
  - Actuator health and Prometheus metrics
  - required SMPP metrics
  - OpenTelemetry tracing exported to Jaeger
  - trace context propagated across the database and RabbitMQ handoff
  - provisioned Grafana dashboard
- Admin APIs:
  - message status counts
  - recent failed messages
  - 60-second processed throughput
- React admin dashboard:
  - served by Docker Compose on port `5173`
  - shows live health, message stats, throughput, failed messages, and Prometheus scrape summaries
- Load-test evidence:
  - Melrose web smoke and Low runs
  - local Cloudhopper client Normal and Overload runs
  - screenshots and result text files under `load-tests/`

## Repository Layout

```text
smpp-observability-platform/
  backend/                         Spring Boot Java 21 Maven project
  frontend/admin-dashboard/         React + TypeScript + Vite dashboard
  infra/docker/                     Dockerfile, docker-compose.yml, WireMock mapping
  infra/prometheus/                 Prometheus scrape configuration
  infra/grafana/                    Grafana datasource and dashboard provisioning
  load-tests/                       Melrose configs, evidence screenshots, local load client
  README.md                         This file
```

Open `backend/` directly in NetBeans. It is the Maven project root and contains `pom.xml`, `src/`,
and the Maven Wrapper.

## Architecture Overview

The application is intentionally layered and small enough to explain in an interview:

```text
SMPP client / load tool
        |
        v
Cloudhopper SMPP server
        |
        v
protocol/smpp
  maps PDUs, creates responses, manages SMPP sessions
        |
        v
application
  validates, throttles, persists, queues, processes, exposes admin queries
        |
        +-----------> repository
        |             PostgreSQL + Flyway, message lifecycle state
        |
        +-----------> RabbitMQ
                      async worker, retry queue, DLQ
        |
        +-----------> mock HTTP sender
```

Package structure:

| Package | Responsibility |
| --- | --- |
| `protocol.smpp` | Cloudhopper-specific SMPP server, session handler, PDU mapping, response creation |
| `application` | Use cases: ingress, queue handoff, processing, completion, admin queries |
| `domain` | Message model, message status, validation, throttling policy |
| `repository` | JPA entity, mapping, persistence queries |
| `integration.queue` | RabbitMQ publisher, consumer, DLQ publisher, queue payload |
| `integration.sender` | Mock HTTP sender client and result classification |
| `observability` | Metrics and trace propagation helpers |
| `admin` | REST controllers for operational inspection |

## Message Lifecycle

```text
submit_sm received
  -> validate + throttle
  -> persist as RECEIVED
  -> return submit_sm_resp immediately
  -> scheduled handoff publishes to RabbitMQ
  -> update to QUEUED
  -> worker calls mock HTTP sender
  -> PROCESSED on success
  -> retry on retryable failure
  -> FAILED + DLQ copy after retry exhaustion or permanent failure
```

Why sync accept and async processing are separate:

- SMPP clients expect a quick `submit_sm_resp`; slow downstream work should not block the protocol
  thread.
- PostgreSQL persistence before `submit_sm_resp` gives durability for accepted messages.
- RabbitMQ isolates background delivery and gives visible retry/DLQ behavior.
- This is a practical assignment-scale architecture, not an enterprise-style distributed design.

## Technology Choices

| Area | Choice | Why |
| --- | --- | --- |
| Java | Java 21 | Modern LTS, works cleanly with Spring Boot 3.x |
| Backend | Spring Boot 3.5.10 | Fast production-style service with Actuator, validation, JPA, AMQP |
| SMPP | Cloudhopper `ch-smpp` 5.0.9 | Mature Java SMPP library, avoids hand-rolling protocol parsing |
| Database | PostgreSQL 16 | Durable relational state and simple operational querying |
| Migrations | Flyway | Repeatable schema setup |
| Queue | RabbitMQ | Clear async handoff, retry queue, DLQ visibility |
| Mock sender | WireMock | Deterministic local HTTP downstream |
| Metrics | Micrometer + Prometheus | Standard Spring observability path |
| Tracing | Micrometer Tracing + OTLP + Jaeger | Easy local trace inspection |
| Dashboard | Grafana | Visualizes required metrics from Prometheus |
| Frontend | React + TypeScript + Vite | Lightweight bonus admin dashboard |
| Deployment | Docker Compose | Reproducible reviewer environment without pretending this is Kubernetes |

## Prerequisites

- Docker Engine
- Java 21
- Node.js 20+ only if running the React dashboard outside Docker

## Run Everything With Docker Compose

From the repository root:

```powershell
docker compose -f infra/docker/docker-compose.yml up -d --build
```

Check containers:

```powershell
docker compose -f infra/docker/docker-compose.yml ps
```

Shut down:

```powershell
docker compose -f infra/docker/docker-compose.yml down
```

If you need a clean database and clean queues:

```powershell
docker compose -f infra/docker/docker-compose.yml down -v
docker compose -f infra/docker/docker-compose.yml up -d --build
```

## Local URLs

| Service | URL |
| --- | --- |
| Backend HTTP | http://localhost:8080 |
| SMPP listener | http://localhost:2775 |
| React admin dashboard | http://localhost:5173 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
| Jaeger | http://localhost:16686 |
| RabbitMQ management | http://localhost:15672 |
| Mock sender | http://localhost:8081 |

Default local credentials:

| Tool | Username | Password |
| --- | --- | --- |
| Grafana | `admin` | `admin` |
| RabbitMQ | `guest` | `guest` |

## Backend APIs

Health:

```powershell
curl.exe -s http://localhost:8080/actuator/health
```

Prometheus scrape:

```powershell
curl.exe -s http://localhost:8080/actuator/prometheus
```

Admin endpoints:

```powershell
curl.exe -s http://localhost:8080/admin/messages/stats
curl.exe -s "http://localhost:8080/admin/messages/failed?limit=20"
curl.exe -s http://localhost:8080/admin/throughput
```

Admin endpoint meanings:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/admin/messages/stats` | Counts by lifecycle status plus total |
| `GET` | `/admin/messages/failed?limit=20` | Recent failed messages with error details |
| `GET` | `/admin/throughput` | Messages processed in the last 60 seconds |

## React Admin Dashboard

Docker Compose serves the built React dashboard at:

```text
http://localhost:5173
```

The dashboard is intentionally operational, not decorative. It shows:

- application health
- cumulative message stats
- recent throughput
- recent failed messages
- selected Prometheus scrape values

To run it locally with hot reload:

```powershell
cd frontend/admin-dashboard
npm install
npm run dev
```

The Vite dev server and the nginx Compose image both proxy `/admin` and `/actuator` to the backend.

## Monitoring

### Required Metrics

Micrometer names use dots in code and underscores in Prometheus text output.

| Code metric | Prometheus metric | Labels | Meaning |
| --- | --- | --- | --- |
| `smpp.messages.received` | `smpp_messages_received_total` | `system_id` | Accepted and persisted `submit_sm` count |
| `smpp.messages.processed` | `smpp_messages_processed_total` | `status` | Terminal async outcomes, `PROCESSED` or `FAILED` |
| `smpp.messages.throttled` | `smpp_messages_throttled_total` | `system_id` | Rejected messages due to throttling |
| `smpp.processing.latency` | `smpp_processing_latency_seconds_*` | `path` | Sync and async processing latency |
| `smpp.sessions.active` | `smpp_sessions_active` | none | Currently bound SMPP sessions |

Useful Prometheus queries:

```promql
sum by (system_id) (rate(smpp_messages_received_total[1m]))
sum by (status) (rate(smpp_messages_processed_total[1m]))
sum by (system_id) (rate(smpp_messages_throttled_total[1m]))
smpp_sessions_active
sum by (path) (increase(smpp_processing_latency_seconds_sum[1m])) / clamp_min(sum by (path) (increase(smpp_processing_latency_seconds_count[1m])), 1)
process_cpu_usage
sum(jvm_memory_used_bytes{area="heap"})
```

Current limitation: latency is exported as summary/count/sum series, not histogram buckets, so
p50/p95/p99 are not available from Prometheus without changing the metric configuration.

### Grafana

Open:

```text
http://localhost:3000
```

The datasource and dashboard are provisioned automatically from `infra/grafana/`.

Dashboard panels include:

- received message rate by `system_id`
- processed message rate by status
- throttled message rate by `system_id`
- active SMPP sessions
- processing latency average by path
- JVM heap usage
- process CPU usage

### Jaeger Tracing

Open:

```text
http://localhost:16686
```

Search for service:

```text
smpp-observability-platform
```

Expected trace shape for `submit_sm`:

```text
submit_sm
  pdu.receive
  input.validation
  db.persist
  queue.publish
  async.worker.processing
```

The app stores W3C `traceparent` in the database and propagates it through RabbitMQ headers so the
async worker continues the same logical trace.

### RabbitMQ

Open:

```text
http://localhost:15672
```

Expected queues:

| Queue | Purpose |
| --- | --- |
| `smpp.messages.process` | Main async processing queue |
| `smpp.messages.process.retry` | TTL delayed retry queue |
| `smpp.messages.process.dlq` | Dead-letter queue for terminal failures |

After successful test runs, all three queues should show:

```text
Ready = 0
Unacked = 0
Total = 0
```

## Run Automated Tests

Windows:

```powershell
cd backend
.\mvnw.cmd test
```

Linux/macOS:

```bash
cd backend
./mvnw test
```

Docker must be running because integration tests use Testcontainers for PostgreSQL and RabbitMQ.

Test coverage includes:

- application context startup
- validation and throttling policies
- ingress persistence behavior
- repository queries
- Admin API controllers
- queue publisher and consumer behavior
- mock sender classification
- SMPP bind, enquire_link, submit_sm, invalid submit, and unbind integration coverage
- RabbitMQ pipeline integration coverage

## Load Testing

The assignment requested Melrose Labs SMPP load testing. Public Melrose web tooling was used where
the free tier allowed it. Higher-rate Normal and Overload runs were executed with the local
Cloudhopper-based SMPP client in `load-tests/local-smpp-load-client/` because the public Melrose page
blocked the required rates/durations without paid access.

This distinction is important:

- Smoke and Low are Melrose web evidence.
- Normal and Overload are local supplemental protocol evidence.
- No numbers below are fabricated.
- Normal and Overload completed with no failures/backlog, but did not sustain the requested target TPS
  end to end on this local machine.

### Evidence Folders

```text
load-tests/1-Smoke Test/
load-tests/2-Low Test/
load-tests/3-Normal Test/
load-tests/4-Overload Test/
```

Each folder contains screenshots and `results.txt`. Normal and Overload also include the exact run
command and recorded duration.

### Results Summary

All tests were cumulative on the same database:

| Scenario | Tool | Target | Measured result | Cumulative total | Failed | Throttled | Notes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| Smoke | Melrose web + ngrok | 1 message | 1 accepted | 1 | 0 | 0 | Basic external SMPP connectivity |
| Low | Melrose web + ngrok | 1200 messages, 10 TPS, 2 min | 1200 accepted in 119.3 sec | 1201 | 0 | 0 | Melrose avg `SubmitSMResp` latency 194.708 ms |
| Normal | Local Cloudhopper client | 30000 messages, 100 TPS command | 30000 processed in 495 sec, 60.6 TPS observed | 31201 | 0 | 0 | Target not sustained; queues drained |
| Overload | Local Cloudhopper client | 66000 messages, 550 TPS command | 66000 processed in 900 sec, 73.3 TPS observed | 97201 | 0 | 0 | Backpressured; no residual backlog |

Final observed state after all runs:

```text
RECEIVED = 0
QUEUED = 0
PROCESSED = 97201
FAILED = 0
TOTAL = 97201
```

### Local Load Client

The local load client is kept in:

```text
load-tests/local-smpp-load-client/
```

Build:

```powershell
cd load-tests/local-smpp-load-client
mvn -q compile
```

Example run from repository root using the backend Maven Wrapper:

```powershell
cd backend
.\mvnw.cmd -q -f ..\load-tests\local-smpp-load-client\pom.xml exec:java "-Dexec.args=--host localhost --port 2775 --system-id loadtest --password loadtest --messages 30000 --tps 100 --binds 2 --submit-window 50 --source 4412345601 --destination 447700900123 --message LOADTEST --request-expiry-ms 60000"
```

Overload command used:

```powershell
cd backend
.\mvnw.cmd -q -f ..\load-tests\local-smpp-load-client\pom.xml exec:java "-Dexec.args=--host localhost --port 2775 --system-id loadtest --password loadtest --messages 66000 --tps 550 --binds 4 --submit-window 100 --source 4412345601 --destination 447700900123 --message LOADTEST --request-expiry-ms 60000"
```

## Configuration

Important environment variables are set in `infra/docker/docker-compose.yml` and can be overridden
through `infra/docker/.env`.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SMPP_SERVER_PORT` | `2775` | SMPP listener port |
| `SMPP_SERVER_ACCEPT_ANY_BIND` | `true` | Accept local test credentials |
| `SMPP_THROTTLE_MAX_TPS_PER_SYSTEM_ID` | `1000` | Per-system-id rolling throttling limit |
| `SMPP_QUEUE_HANDOFF_INTERVAL_MS` | `500` | Poll interval for `RECEIVED` to RabbitMQ handoff |
| `SMPP_QUEUE_BATCH_SIZE` | `50` | Max DB rows promoted per handoff batch |
| `SMPP_QUEUE_MESSAGE_RETRY_TTL_MS` | `2000` | Retry delay through RabbitMQ TTL queue |
| `MOCK_SENDER_MAX_ATTEMPTS` | `3` | Max processing attempts before `FAILED` |
| `SPRING_RABBITMQ_LISTENER_CONCURRENCY` | `1` | Worker consumer concurrency |
| `SPRING_RABBITMQ_LISTENER_PREFETCH` | `10` | RabbitMQ prefetch |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://jaeger:4318/v1/traces` | Trace export endpoint inside Compose |

## Persistence Model

PostgreSQL table `messages` stores the operational lifecycle:

- `message_id`
- source address
- destination address
- message body
- status
- attempt count
- timestamps
- last error
- trace context

Lifecycle states:

```text
RECEIVED -> QUEUED -> PROCESSED
RECEIVED -> QUEUED -> FAILED
```

The status transitions use status-guarded updates where it matters, which protects against duplicate
queue deliveries and repeated terminal processing.

## Concurrency Strategy

- SMPP accept work is short and isolated from downstream delivery.
- Application-owned SMPP worker execution is bounded.
- Accepted messages are durable before the SMPP response is returned.
- RabbitMQ handles async delivery and retry delays.
- RabbitMQ listener concurrency and prefetch are configurable.
- The throttle policy is in-memory and per `system_id`, which is appropriate for a single-instance
  Docker Compose assignment.

## Failure Handling

| Failure | Behavior |
| --- | --- |
| Invalid `submit_sm` | SMPP error response, no message persisted |
| Throttled submit | SMPP throttled response, metric incremented |
| Database failure during accept | SMPP system error response |
| Queue publish delay/failure | Message remains `RECEIVED`; scheduled handoff retries |
| Retryable mock sender failure | Retry through RabbitMQ TTL queue |
| Permanent mock sender failure | Message marked `FAILED`, copied to DLQ |
| Retry exhaustion | Message marked `FAILED`, copied to DLQ |
| Duplicate queue delivery | Already terminal messages are skipped safely |

## Assumptions

- Single-instance local assignment environment.
- PostgreSQL is the durable source of truth for message status.
- RabbitMQ is used for async processing, retries, and DLQ visibility.
- The mock HTTP sender represents a downstream SMS delivery provider.
- Default Compose bind mode accepts any credentials to simplify local evaluation.
- Melrose public web tooling may require an externally reachable SMPP endpoint such as an ngrok TCP
  tunnel.

## Limitations

- Normal and Overload were not executed through paid Melrose infrastructure; they used the local
  Cloudhopper client.
- Normal and Overload did not sustain the requested target TPS end to end on this machine.
- Latency percentiles are not available from current Prometheus timer output because histogram
  buckets are not enabled.
- Throttling was implemented and observable, but the recorded overload run backpressured rather than
  producing throttled responses.
- The in-memory throttler is not distributed across multiple app instances.
- No authentication is added to Admin APIs because the assignment scope is local evaluation.

## Future Improvements

- Add client-side latency histograms to the local load client, or enable Micrometer histogram buckets
  for p50/p95/p99 reporting.
- Tune handoff batch size, RabbitMQ listener concurrency/prefetch, database writes, and mock sender
  latency if sustained 100/500+ TPS becomes a hard target.
- Add structured JSON logs with `message_id` and trace IDs.
- Add per-`system_id` bind credential configuration.
- Add delivery receipt support if the mock sender contract requires it.
- Add security for Admin APIs in a non-local environment.

## Interview Notes

- The most important design decision is separating fast SMPP acceptance from slower delivery work.
- PostgreSQL persistence before `submit_sm_resp` makes accepted messages durable.
- RabbitMQ is chosen for understandable retry and DLQ behavior, not because this assignment needs a
  large distributed architecture.
- Observability is built around the message lifecycle: counters for events, timers for sync/async
  work, session gauge for bound clients, and traces from protocol receive through async worker.
- The load-test evidence is intentionally honest: strong Low/Melrose evidence, larger local runs,
  no fabricated percentile or sustained-TPS claims.
