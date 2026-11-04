# Dropoff build plan (M0–M8)

This is the build order for Dropoff. Every decision it relies on is settled in a closed ticket on the [wayfinder map](https://github.com/Danie1Le/Dropoff/issues/2) or in an ADR under `docs/adr/`. Domain words follow `GLOSSARY.md`. When this plan and a ticket disagree, the ticket wins; fix the plan in the same PR.

Tickets marked _(delegated)_ on the map were decided by Claude on the user's instruction. The user reviews them along with this plan.

## How every PR works

1. Branch from `main` (`feat/…`, `chore/…`, `docs/…`). Use Conventional Commits, and add no AI attribution lines.
2. Write domain rules test-first (state machine, dispatch rules). Plumbing gets tests after.
3. Before opening the PR, run the `code-review` skill (Standards + Spec) and fix its findings.
4. CI is green: Spotless, Error Prone + NullAway, unit tests (`*Test`), integration tests (`*IT`, Testcontainers).
5. In `docs/roadmap.html`, set `done: true` on finished tasks and bump the "Data last synced" date.
6. The user reviews the PR and gets a short quiz on what it taught.

Each PR below lists its **scope**, **tests** and **decisions** (ticket numbers on the map, ADR numbers in `docs/adr/`).

## Decision index

| Area | Where it is decided |
|---|---|
| Foundations (monolith first, JPA + Flyway, BIGINT ids, cents, state machine, Redis geo, events, STOMP) | #3, ADR-0001–0004 |
| Versions (Spring Boot 4.1.2, Modulith 2.1.2, springdoc 3.2.0, Gradle 9.8.2) | #5 |
| Docker images | #6 |
| Coding standards | #7 |
| API conventions (`/api/v1`, Problem Details, paging, 404/409/422) | #8 |
| Endpoints, delivery address snapshot | #9 |
| Order command endpoints | #10, ADR-0005 |
| Offer lifecycle | #11 |
| Courier location | #12 |
| Stuck orders | #13 |
| Background jobs (`SKIP LOCKED`) | #14 |
| Outbox | #15, ADR-0006 |
| Service split | #16, ADR-0007 |
| Gradle layout | #31 |
| Kafka topics | #32 |
| Demo data and simulator | #33 |
| Metrics and Grafana | #34 |
| M7 method | #35 |
| Images | #36 |
| README | #37 |

---

## M0 — Project setup

Goal: an empty app that builds, tests and runs in Docker, with CI on every push.

**M0-1 Gradle multi-project skeleton.**
- Scope:
  - Gradle 9.8.2 wrapper with the Kotlin DSL.
  - `settings.gradle.kts` with the foojay resolver.
  - `build-logic/` convention plugin `dropoff.java-conventions`: Java 21 toolchain, Spotless (palantir), Error Prone + NullAway (standard mode, `OnlyNullMarked=true`), JaCoCo report, and separate `test` (`*Test`) and `integrationTest` (`*IT`) tasks.
  - `gradle/libs.versions.toml`.
  - An empty `app/` subproject.
- Tests: the build passes on an empty project.
- Decisions: #5, #7, #31.

**M0-2 Spring Boot app and module skeleton.**
- Scope:
  - Spring Boot 4.1.2 in `app/` (webmvc, data-jpa, validation, actuator).
  - Root package `io.github.danie1le.dropoff`, with packages `merchant`, `order`, `dispatch` and `common`, each `@NullMarked`.
  - Spring Modulith 2.1.2.
- Tests: an `ApplicationModules.verify()` test, and a context-loads test.
- Decisions: #7, #31, ADR-0001.

**M0-3 Local infrastructure and database baseline.**
- Scope:
  - `compose.yaml` with `postgres:18.6-alpine` and `redis:8.10.3-alpine`.
  - Flyway with an empty baseline `V1__baseline.sql`.
  - `spring.jpa.hibernate.ddl-auto=validate`.
- Tests: a Testcontainers 2 smoke IT against real Postgres (Flyway runs, context starts).
- Decisions: #6, ADR-0002.

**M0-4 CI and standards.**
- Scope:
  - A GitHub Actions workflow on every push and PR (Temurin 21; `spotlessCheck`, `build`, `integrationTest`).
  - The `main` ruleset requires this check.
  - `CODING_STANDARDS.md`, written from the #7 resolution.
- Tests: CI runs green on the PR itself.
- Decisions: #4, #7.

## M1 — Merchants & menus

Goal: merchants can list what they sell.

**M1-1 Error handling and paging foundation (`common`).**
- Scope:
  - `DomainException` with `code` and a category (`NOT_FOUND` / `CONFLICT` / `UNPROCESSABLE`).
  - One `@RestControllerAdvice`: Problem Details (`spring.mvc.problemdetails.enabled=true`) with a `code` field; `VALIDATION_FAILED` with `errors[{field,message}]`; `MALFORMED_REQUEST`; `INTERNAL_ERROR` with no internals leaked.
  - The `/api/v1` prefix, configured once.
  - Paging limits: default 20, max 100, above the max returns 400.
- Tests: web-slice tests for each error shape and the paging limits.
- Decisions: #8.

**M1-2 Merchants.**
- Scope:
  - Migration `merchants` (`id BIGINT identity`, name, address, lat, lng, `is_open`).
  - `POST /api/v1/merchants`, `GET /api/v1/merchants/{id}`, `GET /api/v1/merchants?page=` (`PagedModel`), `PATCH /api/v1/merchants/{id}` (open, name, address).
  - Records as DTOs; 201 + `Location`.
- Tests: web-slice and repository ITs.
- Decisions: #8, #9.

**M1-3 Menu items and menu.**
- Scope:
  - Migration `menu_items` (`price_cents BIGINT CHECK >= 0`, `is_available`).
  - `POST /api/v1/merchants/{id}/menu-items`, `PATCH /api/v1/menu-items/{id}` (available, name, priceCents).
  - `GET /api/v1/merchants/{id}/menu` returns all items with an `available` flag.
  - No DELETE.
- Tests: web-slice and repository ITs; price CHECK test.
- Decisions: #3, #9.

**M1-4 Swagger UI.**
- Scope: springdoc 3.2.0 with grouped `/api/v1` docs; error responses documented.
- Tests: an IT asserting `/v3/api-docs` loads and lists the endpoints.
- Decisions: #5.

## M2 — Orders

Goal: customers place orders that move through a safe lifecycle.

**M2-1 Customers.**
- Scope:
  - Migration `customers`, in the `order` module.
  - `POST/GET/PATCH /api/v1/customers`.
  - lat −90..90 and lng −180..180 validation; no geocoder.
- Tests: web-slice and repository ITs.
- Decisions: #9.

**M2-2 Order schema and state machine (TDD).**
- Scope:
  - Migrations: `orders` (status CHECK, `total_cents`, delivery address snapshot columns, `idempotency_key UNIQUE`, `request_hash`, `courier_id` nullable), `order_lines` (price snapshot), `order_events` (from/to status, `actor` CHECK), and Modulith `event_publication`.
  - Cross-module FKs per ADR-0003.
  - A pure-Java state machine: transitions, actors, and repeated-command no-op.
- Tests: state machine unit tests written first (every legal and illegal transition, actor rules, no-op).
- Decisions: #3, #9, #10, #15, ADR-0003, ADR-0004.

**M2-3 Place order.**
- Scope:
  - `POST /api/v1/orders` (ids + quantities only).
  - The server computes the total, copies price snapshots and copies the customer's address as the delivery address.
  - Errors: 422 for an unavailable item, a wrong-merchant item, an unknown item, or `MERCHANT_CLOSED`.
  - Publishes `OrderPlaced`, a record with `eventId` + `occurredAt`.
- Tests: an IT for each rule; total and snapshot tests.
- Decisions: #3, #8, #9, #15.

**M2-4 Idempotency key.**
- Scope:
  - `Idempotency-Key` header.
  - The same key with the same body replays the original 201, with `Idempotent-Replayed: true`.
  - The same key with a different body returns 422.
  - A concurrent duplicate returns 409.
- Tests: ITs for replay, a changed body, and a concurrent race.
- Decisions: #3, #8.

**M2-5 Order commands and events.**
- Scope:
  - `GET /api/v1/orders/{id}`.
  - Merchant `accept` / `start-preparing` / `ready` / `cancel` and customer `cancel`, as path-scoped POSTs with no body, returning 200 + the order.
  - Wrong caller returns 404, illegal step 409, repeat 200 no-op.
  - Status changes use a conditional `UPDATE … WHERE status IN (…)`.
  - Each change writes an `order_events` row with its actor.
  - Publishes `OrderCancelled` and `OrderReady` via `@ApplicationModuleListener` / the registry.
- Tests: ITs for each command, the race between two cancels, and events written to `event_publication`.
- Decisions: #10, #13, #15, ADR-0005, ADR-0006.

**M2-6 Order lists.**
- Scope:
  - `GET /api/v1/merchants/{id}/orders?status=&page=`: oldest first.
  - `GET /api/v1/customers/{id}/orders?page=`: newest first.
  - Both use hand-written SQL.
- Tests: repository ITs for sort order, filter and paging.
- Decisions: #9, ADR-0002.

**M2-7 Auto-cancel job.**
- Scope:
  - A `@Scheduled` job every 30 s. Orders PLACED for longer than the 10 min limit (`dropoff.orders.auto-cancel-after`) become CANCELLED with actor `SYSTEM`.
  - Rows are claimed with `FOR UPDATE SKIP LOCKED`, one row per transaction, at most 50 per run.
- Tests: an IT with a clock fixture; an IT where two job runs never cancel the same order twice.
- Decisions: #13, #14.

## M3 — Dispatch

Goal: ready orders get the nearest available courier, with reassignment.

**M3-1 Couriers and location.**
- Scope:
  - Migration `couriers`.
  - `POST /api/v1/couriers`, `PATCH /api/v1/couriers/{id}/status`, `PUT /api/v1/couriers/{id}/location`.
  - Every location update writes Postgres. AVAILABLE couriers are also added with `GEOADD`; OFFLINE returns 409 `COURIER_OFFLINE`.
  - The Redis geo set holds AVAILABLE couriers only.
- Tests: ITs with Postgres + Redis containers.
- Decisions: #3, #12.

**M3-2 Dispatch on READY.**
- Scope:
  - Migration `offers` (status, `offered_at`, `expires_at`, `responded_at`) and the partial unique index `one_open_offer_per_order`.
  - Listener on `OrderReady`: `GEOSEARCH` 5 km nearest first, excluding couriers who had an offer for this order in the last 5 min.
  - Claim the courier with `UPDATE couriers … WHERE status='AVAILABLE'`, then insert an OFFERED offer expiring in 30 s.
  - A retry job every 10 s for READY orders with no courier and no open offer (`SKIP LOCKED`, max 50).
- Tests:
  - TDD unit tests for candidate selection and exclusion.
  - ITs for a two-order race on one courier, the listener-vs-job race blocked by the unique index, and the retry.
- Decisions: #3, #11, #13, #14.

**M3-3 Offer endpoints and assignment.**
- Scope:
  - `GET /api/v1/couriers/{id}/offers/current` (200 or 204).
  - `POST …/offers/{offerId}/accept` and `decline`.
  - Late accept is blocked by a conditional `UPDATE … AND expires_at > now()` and returns 409 `OFFER_CLOSED`. Another courier's offer returns 404; a repeat returns 200 no-op.
  - Accept publishes `CourierAssigned`; an order-module listener sets `orders.courier_id`.
  - Decline sends the courier back to AVAILABLE (re-added with `GEOADD`) and dispatches the next courier.
- Tests: ITs for accept, decline, late accept and the wrong courier.
- Decisions: #11.

**M3-4 Timeout, offline and reassignment.**
- Scope:
  - A timeout job every 5 s (`SKIP LOCKED`): the offer becomes TIMED_OUT, the courier OFFLINE, and the next courier is dispatched.
  - Going OFFLINE with an open offer declines it.
  - Going OFFLINE after accepting but before pickup publishes `CourierUnassigned` (`courier_id` cleared, then a new dispatch).
  - After pickup, OFFLINE returns 409.
- Tests: ITs for each path.
- Decisions: #3, #11, #14.

**M3-5 Pickup and delivery.**
- Scope:
  - Courier `pick-up` / `deliver` commands, which live in the order module.
  - These publish `OrderPickedUp` and `OrderDelivered`.
  - On `OrderDelivered`, dispatch sets the courier AVAILABLE and re-adds them to the geo set.
- Tests: ITs, including the wrong courier (404) and deliver before pick-up (409).
- Decisions: #10, #11.

**M3-6 Simulator: seed and bots.**
- Scope:
  - The `tools/simulator` subproject: Java 21, `HttpClient`, Jackson, no Spring.
  - `seed` creates the Austin dataset (10 × 8 items, 50 customers, 30 couriers) through the API.
  - `run` drives customer bots (6 orders/min), merchant bots (5–20 s per step) and courier bots (poll every 2 s; 80/15/5 accept/decline/ignore; about 30 km/h straight line; location every 3 s).
  - Fixed random seed.
  - Compose profile `demo`.
- Tests: unit tests for movement and decision logic; a manual end-to-end run documented in the PR.
- Decisions: #33.

## M4 — Live tracking

Goal: customers watch their order and courier move live.

**M4-1 STOMP status updates.**
- Scope:
  - STOMP over WebSocket at `/ws` (order module).
  - Each status change is pushed to `/topic/orders/{id}`, including SYSTEM cancels.
- Tests: an IT with a STOMP client receiving each status.
- Decisions: #3, #13.

**M4-2 Courier location stream.**
- Scope:
  - Dispatch publishes `CourierLocationUpdated` (`{courierId, orderId, lat, lng, at}`) only for a courier with an accepted, not-yet-delivered offer.
  - It is limited to one per courier every 5 s via `SET courier:{id}:loc-pub 1 NX EX 5`.
  - It uses a plain `@EventListener` (no registry). The order module pushes it to the topic.
- Tests: an IT showing updates at 0 s, 2 s and 6 s produce 2 pushes and 3 Postgres writes.
- Decisions: #12, #15.

**M4-3 Simulator watch.**
- Scope: `simulator watch <orderId>` prints the status and coordinates from the topic.
- Tests: a manual check documented in the PR.
- Decisions: #33.

## M5 — Kafka & service split

Goal: three services talking through durable events. Kafka comes first, inside the monolith; then the split, one service at a time.

**M5-1 Kafka in the monolith.**
- Scope:
  - `apache/kafka:4.4.0` (KRaft) in Compose with `auto.create.topics.enable=false`.
  - `NewTopic` beans for `order-events`, `courier-events`, `courier-locations`, `merchants` (compacted), `menu-items` (compacted) and their `.DLT` topics: 3 partitions, RF 1, retention per #32.
  - `spring-modulith-events-kafka` with `@Externalized("<topic>::#{orderId()}")`.
  - `CourierLocationUpdated` sent with `KafkaTemplate`.
  - JSON with logical type names.
  - Consumers dedupe with `processed_events`; `DefaultErrorHandler` makes 3 redelivery attempts (1/2/4 s), then sends the message to the DLT.
  - `ops/kafka/replay-dlt.sh`.
  - `spring.modulith.events.republish-outstanding-events-on-restart=true`.
- Tests:
  - Testcontainers Kafka ITs: an event reaches the topic after commit and not after a rollback; a duplicate is skipped; a poison message ends in the DLT.
- Decisions: #15, #32, ADR-0006.

**M5-2 Contracts and web-common.**
- Scope:
  - Move the event records into a `contracts` module (records only, no Spring, additive changes only).
  - Move `DomainException`, the advice and the paging limits into `libs/web-common`.
  - Still one app.
- Tests: all existing tests stay green.
- Decisions: #31.

**M5-3 merchant-service.**
- Scope:
  - `services/merchant` on port 8081 with `merchant_db`.
  - A Postgres init script creates the three databases and their users.
  - Publishes `MerchantChanged` (key `merchantId`) and `MenuItemChanged` (key `menuItemId`) to the compacted topics.
- Tests: service ITs and a Kafka publish IT.
- Decisions: #16, #32, ADR-0007.

**M5-4 order-service.**
- Scope:
  - `services/order` on port 8082 with `order_db`.
  - `merchant_copies` and `menu_item_copies`, filled by consumers and rebuilt by replay from offset 0 on an empty start.
  - Order validation reads the copies.
  - `OrderReady` carries `merchantLat` / `merchantLng`.
  - WebSocket here; consumes `courier-events` and `courier-locations`.
- Tests: ITs for copy rebuild, ordering while merchant-service is down, and an unknown item returning 422.
- Decisions: #12, #16, ADR-0007.

**M5-5 dispatch-service and cleanup.**
- Scope:
  - `services/dispatch` on port 8083 with `dispatch_db` + Redis.
  - Consumes `order-events`.
  - Drops cross-service FKs.
  - Removes `app/`.
  - Each service keeps a Modulith verify test.
  - Simulator base URLs for three services.
  - Fresh databases, seeded by the simulator; no data migration.
- Tests: a full-flow IT across the three services in Compose or Testcontainers.
- Decisions: #11, #16, #31, ADR-0003, ADR-0007.

## M6 — Ops

Goal: containers, published images and dashboards.

**M6-1 Metrics.**
- Scope:
  - Actuator `/actuator/prometheus` and Prometheus v3.13.5 in Compose (15 s scrape).
  - The `dropoff_*` metrics from #34. Gauges come from a cached 15 s SQL query.
  - Percentile histograms on `http_server_requests_seconds`.
- Tests: ITs asserting the metric names appear and the counters move.
- Decisions: #34.

**M6-2 Grafana as code.**
- Scope:
  - `ops/grafana/provisioning/` with the datasource, the dashboard JSON (9 panels) and 3 alert rules (waiting > 15 min, DLT messages, p95 > 500 ms).
  - No notification channel.
- Tests: Compose starts Grafana with the dashboard loaded (manual check plus a screenshot in the PR).
- Decisions: #13, #34.

**M6-3 Dockerfiles.**
- Scope:
  - One Dockerfile per service: layered jar extract on `eclipse-temurin:21-jre-alpine`, non-root user.
  - Compose can run the built images.
- Tests: CI builds each image; the size is checked against < 300 MB.
- Decisions: #36.

**M6-4 Publish to GHCR.**
- Scope:
  - A workflow pushes `ghcr.io/danie1le/dropoff-<service>`: `sha-<short>` and `main` on main; `<version>` and `latest` on `v*` tags.
  - PRs build but don't push.
  - linux/amd64; `GITHUB_TOKEN` with `packages: write`.
- Tests: the workflow run on main shows the pushed tags.
- Decisions: #36.

## M7 — Performance

Goal: measured load and one or two measured wins.

**M7-1 Load test harness.**
- Scope:
  - `ops/k6/` scripts for `place-orders` (5→50 orders/s over 10 min), `browse` (100 req/s) and `order-history` (deep pages), with thresholds p95 < 300 ms and errors < 1%.
  - Compose profile `load` (`grafana/k6:2.3.0`).
  - `ops/perf/seed-history.sql` (~200k orders).
  - `pg_stat_statements` enabled.
- Tests: each script runs to completion locally.
- Decisions: #35.

**M7-2 Baseline.**
- Scope:
  - A median of 3 runs per scenario: throughput, p50/p95/p99, errors, dispatch p95, top 5 queries. Hardware and SHA recorded.
  - `docs/perf/README.md` started.
- Tests: none (a measurement PR).
- Decisions: #35.

**M7-3 and M7-4 Optimizations (one or two PRs).**
- Scope:
  - Pick from: index, cache, batching, offset→keyset paging, location writes to Redis.
  - Choose by the share of the worst p95 each explains (`pg_stat_statements` + `EXPLAIN (ANALYZE, BUFFERS)`).
  - Each PR body carries the before/after table.
- Tests: existing tests stay green; the k6 numbers come from the same harness.
- Decisions: #35.

**M7-5 Write-up.**
- Scope:
  - `docs/perf/README.md` complete: setup, baseline, each change with EXPLAIN, the after numbers and the % change.
  - A summary table in the README.
- Decisions: #35.

## M8 — Polish

Goal: a repo a reviewer can understand at a glance.

**M8-1 Swagger completeness.**
- Scope: every endpoint has a summary, examples and error responses across the three services.
- Tests: an IT per service that `/v3/api-docs` lists all endpoints.
- Decisions: #8.

**M8-2 README.**
- Scope: the 15 sections from #37, a Mermaid architecture diagram, a quick start (ports 8081/8082/8083, Swagger URLs, Grafana on 3000) and known gaps (no auth, no payments).
- Decisions: #37.

**M8-3 AI usage notes.**
- Scope: `docs/ai-usage.md` covers the tools, how output was checked (tests, code-review on every PR, user review, quizzes) and the mistakes caught. The README links to it.
- Decisions: #37.

**M8-4 Demo media.**
- Scope:
  - A demo GIF under 30 s and under 10 MB (simulator `watch` + Grafana, ScreenToGif).
  - Screenshots of Swagger, Grafana and the k6 summary, in `docs/media/`.
- Decisions: #37.

---

## Out of scope

Authentication and authorization (a known gap noted in the README), payments and refunds, an API gateway, a maximum delivery distance, widening the dispatch radius, image vulnerability scanning, and the stretch goals (ETA, batching, surge pricing, Kotlin service, map UI, hosted deploy, broadcast offers, road-distance routing). See the map's Out of scope section.
