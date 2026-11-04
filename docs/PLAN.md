# Dropoff build plan (M0–M8)

This is the build order for Dropoff. Every decision it relies on is settled in a closed ticket on the [wayfinder map](https://github.com/Danie1Le/Dropoff/issues/2) or in an ADR under `docs/adr/`. Domain words follow `GLOSSARY.md`. When this plan and a ticket disagree, the ticket wins; fix the plan in the same PR.

Tickets marked _(delegated)_ on the map (#32–#37, #42) were decided by Claude on the user's instruction. The user reviews them along with this plan.

## How every PR works

1. Branch from `main` (`feat/…`, `chore/…`, `docs/…`). Use Conventional Commits, and add no AI attribution lines.
2. Write domain rules test-first (the order status rules, dispatch candidate selection). Plumbing gets tests after.
3. Before opening the PR, run the `code-review` skill (Standards + Spec) and fix its findings.
4. CI is green: Spotless, Error Prone + NullAway, unit tests (`*Test`), integration tests (`*IT`, Testcontainers).
5. In `docs/roadmap.html`, set `done: true` on finished tasks and bump the "Data last synced" date.
6. The user reviews the PR and gets a short quiz on what it taught.

Each PR lists its **scope**, **tests** and **decisions** (`#n` = ticket on the map, `ADR-n` = `docs/adr/`).

## Decision index

| Area | Decided in |
|---|---|
| Foundations (monolith first, JPA + Flyway, BIGINT ids, cents, order status rules, Redis geo, events, STOMP) | #3, ADR-0001–0004 |
| Versions (Spring Boot 4.1.2, Modulith 2.1.2, springdoc 3.2.0, Gradle 9.8.2) | #5 |
| Docker images | #6 |
| Coding standards | #7 |
| API conventions | #8 (amended by #42) |
| Endpoints, delivery address snapshot | #9 |
| Order command endpoints | #10, ADR-0005 |
| Offer lifecycle | #11 |
| Courier location | #12 |
| Stuck orders | #13 |
| Background jobs (`SKIP LOCKED`) | #14 |
| Outbox | #15, ADR-0006 |
| Service split | #16 (amended twice), ADR-0007 |
| Gradle layout | #31 |
| Kafka topics | #32 |
| Demo data and simulator | #33 |
| Metrics and Grafana | #34 |
| M7 method | #35 |
| Images | #36 |
| README | #37 |
| Gate findings: payloads, error codes, shapes, courier rules, outbox settings, M5 order | #42 |

## Shared contracts

These apply across many PRs, and #42 is the source for all of them.

**Events.** Every event is a Java record with `eventId` (UUID) and `occurredAt`, plus:

| Event | Fields | Published by | From |
|---|---|---|---|
| `OrderPlaced` | orderId, customerId, merchantId, totalCents | order | M2-3 |
| `OrderCancelled` | orderId, merchantId, actor | order | M2-5 |
| `OrderReady` | orderId, merchantId, merchantName, merchantAddress, pickupLat, pickupLng, deliveryAddress, deliveryLat, deliveryLng, readyAt | order | M2-5 |
| `CourierAssigned` | orderId, courierId, offerId | dispatch | M3-3 |
| `CourierUnassigned` | orderId, courierId | dispatch | M3-4 |
| `OrderPickedUp` | orderId, courierId | order | M3-5 |
| `OrderDelivered` | orderId, courierId | order | M3-5 |
| `CourierLocationUpdated` | courierId, orderId, lat, lng, at | dispatch | M4-2 |
| `MerchantChanged` | merchantId, name, address, open, lat, lng | merchant | M5-3 |
| `MenuItemChanged` | menuItemId, merchantId, name, priceCents, available | merchant | M5-3 |

**Error codes** (Problem Details `code` field):
- 400: `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `IDEMPOTENCY_KEY_REQUIRED`.
- 404: `MERCHANT_NOT_FOUND`, `MENU_ITEM_NOT_FOUND`, `CUSTOMER_NOT_FOUND`, `ORDER_NOT_FOUND`, `COURIER_NOT_FOUND`, `OFFER_NOT_FOUND`.
- 409: `INVALID_STATUS_TRANSITION`, `OFFER_CLOSED`, `COURIER_OFFLINE`, `COURIER_BUSY`, `COURIER_LOCATION_UNKNOWN`, `COURIER_HAS_ACTIVE_DELIVERY`.
- 422: `MENU_ITEM_UNAVAILABLE`, `MENU_ITEM_WRONG_MERCHANT`, `MENU_ITEM_UNKNOWN`, `MERCHANT_CLOSED`, `IDEMPOTENCY_KEY_REUSED`.
- 500: `INTERNAL_ERROR`.

**Order body** (from `GET /api/v1/orders/{id}` and every order command): `{id, customerId, merchantId, courierId|null, status, totalCents, deliveryAddress: {address, lat, lng}, lines: [{menuItemId, quantity, priceCents}], createdAt, updatedAt}`.

**STOMP messages** on `/topic/orders/{id}`:
- `{type: "STATUS", orderId, status, actor, at}`
- `{type: "LOCATION", orderId, courierId, lat, lng, at}`

**Outbox settings** (from M2):
- JDBC event publication registry (`spring-modulith-starter-jdbc`).
- `spring.modulith.events.completion-mode=delete`.
- `spring.modulith.events.republish-outstanding-events-on-restart=true`.
- Schema initialization off. The `event_publication` table comes from a Flyway migration copied from the Modulith 2.1.2 PostgreSQL schema.

---

## M0 — Project setup

Goal: an empty app that builds, tests and runs locally against Postgres and Redis in Docker, with CI on every push.

**M0-1 Gradle multi-project skeleton.**
- Scope:
  - Gradle 9.8.2 wrapper with the Kotlin DSL.
  - `settings.gradle.kts` with the foojay resolver.
  - `build-logic/` convention plugin `dropoff.java-conventions`: Java 21 toolchain, Spotless (palantir), Error Prone + NullAway (standard mode, `OnlyNullMarked=true`), JaCoCo report, and separate `test` (`*Test`) and `integrationTest` (`*IT`) tasks.
  - `gradle/libs.versions.toml`.
  - An empty `app/` subproject.
- Tests: the build passes on the empty project.
- Decisions: #5, #7, #31.

**M0-2 Spring Boot app and modules.**
- Scope:
  - Spring Boot 4.1.2 in `app/` (webmvc, data-jpa, validation, actuator).
  - Root package `io.github.danie1le.dropoff`, with packages `merchant`, `order`, `dispatch` and `common`, each `@NullMarked`.
  - Spring Modulith 2.1.2.
- Tests: `ApplicationModules.verify()`; context loads.
- Decisions: #7, #31, ADR-0001.

**M0-3 Local infrastructure and database baseline.**
- Scope:
  - `compose.yaml` with `postgres:18.6-alpine` and `redis:8.10.3-alpine`.
  - Flyway `V1__baseline.sql`.
  - `ddl-auto=validate`.
  - The app runs with `./gradlew :app:bootRun`.
- Tests: a Testcontainers 2 smoke IT (Flyway runs, context starts).
- Decisions: #6, ADR-0002.

**M0-4 CI and standards.**
- Scope:
  - A GitHub Actions workflow on every push and PR (Temurin 21; `spotlessCheck`, `build`, `integrationTest`).
  - The `main` ruleset requires it.
  - `CODING_STANDARDS.md`, written from #7.
- Tests: CI green on the PR itself.
- Decisions: #4, #7.

## M1 — Merchants & menus

Goal: merchants can list what they sell.

**M1-1 Error handling and paging (`common`).**
- Scope:
  - `DomainException` with `code` and a category (`NOT_FOUND` / `CONFLICT` / `UNPROCESSABLE`).
  - One `@RestControllerAdvice` producing Problem Details with `code`.
  - `VALIDATION_FAILED` with `errors[{field,message}]` for body and parameters.
  - `MALFORMED_REQUEST`.
  - A path-variable type mismatch returns 400.
  - `INTERNAL_ERROR` with no internals leaked.
  - The `/api/v1` prefix, configured once.
  - Paging: default 20, max 100, above returns 400; `PagedModel` responses.
- Tests: web-slice tests for each error shape and the paging limits.
- Decisions: #8, #42.

**M1-2 Merchants.**
- Scope:
  - Migration `merchants` (`id BIGINT identity`, name, address, lat, lng, `is_open` default true).
  - `POST /api/v1/merchants`, `GET /api/v1/merchants/{id}`, `GET /api/v1/merchants?page=`.
  - `PATCH /api/v1/merchants/{id}` (open, name; address + lat + lng only together).
  - Record DTOs; 201 + `Location`.
- Tests: web-slice tests and repository ITs, including a partial address returning 400.
- Decisions: #8, #9, #42.

**M1-3 Menu items and menu.**
- Scope:
  - Migration `menu_items` (`price_cents BIGINT CHECK >= 0`, `is_available`).
  - `POST /api/v1/merchants/{id}/menu-items`, `PATCH /api/v1/menu-items/{id}` (available, name, priceCents).
  - `GET /api/v1/merchants/{id}/menu` returns all items with an `available` flag.
  - No DELETE.
- Tests: web-slice tests and repository ITs; price CHECK.
- Decisions: #3, #9.

**M1-4 Swagger UI.**
- Scope: springdoc 3.2.0, `/api/v1` group, error responses documented.
- Tests: an IT that `/v3/api-docs` lists the endpoints.
- Decisions: #5.

## M2 — Orders

Goal: customers place orders that move through a safe lifecycle.

**M2-1 Customers.**
- Scope:
  - Migration `customers` (order module).
  - `POST/GET/PATCH /api/v1/customers`.
  - lat −90..90, lng −180..180; address + lat + lng change together; no geocoder.
- Tests: web-slice tests and repository ITs.
- Decisions: #9, #42.

**M2-2 Order schema and order status rules (TDD).**
- Scope:
  - Migrations:
    - `orders`: status CHECK, `total_cents`, `delivery_address` / `delivery_lat` / `delivery_lng`, `idempotency_key UNIQUE`, `request_hash`, `courier_id` nullable, `created_at` / `updated_at` TIMESTAMPTZ.
    - `order_lines`: price snapshot.
    - `order_events`: from/to status, `actor` CHECK, `created_at`.
    - `event_publication`: the Modulith 2.1.2 schema.
  - Cross-module FKs per ADR-0003.
  - Outbox settings from "Shared contracts".
  - Pure-Java order status rules: transitions, which actor may take each step, and repeated-command no-op.
- Tests: unit tests written first, covering every legal and illegal step, the actor rules and the no-op.
- Decisions: #3, #9, #10, #15, #42, ADR-0003, ADR-0004, ADR-0006.

**M2-3 Place order.**
- Scope:
  - `POST /api/v1/orders` (customerId, merchantId, lines of menuItemId + quantity).
  - The server computes the total and copies price snapshots and the customer's address.
  - 422 errors: `MENU_ITEM_UNKNOWN`, `MENU_ITEM_UNAVAILABLE`, `MENU_ITEM_WRONG_MERCHANT`, `MERCHANT_CLOSED`.
  - Publishes `OrderPlaced`.
- Tests: an IT for each rule; total and snapshot tests; event published (Modulith `Scenario`).
- Decisions: #3, #8, #9, #42.

**M2-4 Idempotency key.**
- Scope:
  - The `Idempotency-Key` header is required (missing returns 400 `IDEMPOTENCY_KEY_REQUIRED`).
  - `request_hash` = SHA-256 of the canonical request (lines sorted by menuItemId).
  - Same key + same hash replays 201 with `Idempotent-Replayed: true`. Same key + different hash returns 422 `IDEMPOTENCY_KEY_REUSED`.
  - A concurrent duplicate waits on the unique index, then replays, gets 422, or proceeds if the first rolled back.
- Tests: ITs for replay, a changed body, a missing header, and two concurrent requests.
- Decisions: #3, #8, #42.

**M2-5 Order commands and events.**
- Scope:
  - `GET /api/v1/orders/{id}` (order body from "Shared contracts").
  - Merchant `accept` / `start-preparing` / `ready` / `cancel` and customer `cancel`: path-scoped POSTs with no body, returning 200 + the order.
  - A wrong caller gets 404 (`ORDER_NOT_FOUND`), an illegal step 409 `INVALID_STATUS_TRANSITION`, a repeat 200 no-op.
  - A conditional `UPDATE … WHERE status IN (…)`; an `order_events` row with its actor.
  - Publishes `OrderCancelled` and `OrderReady` (all fields).
- Tests: ITs for each command and for two racing cancels; events published (Modulith `Scenario`).
- Decisions: #10, #13, #15, #42, ADR-0005.

**M2-6 Order lists.**
- Scope:
  - `GET /api/v1/merchants/{id}/orders?status=&page=`: oldest first.
  - `GET /api/v1/customers/{id}/orders?page=`: newest first.
  - Hand-written SQL.
- Tests: repository ITs for sort order, filter and paging.
- Decisions: #9, ADR-0002.

**M2-7 Auto-cancel job.**
- Scope:
  - Every 30 s, orders PLACED for longer than `dropoff.orders.auto-cancel-after` (10 min) become CANCELLED with actor `SYSTEM`, and `OrderCancelled` is published.
  - `FOR UPDATE SKIP LOCKED`, one row per transaction, at most 50 per run.
- Tests: an IT with a clock fixture; an IT where two concurrent runs never cancel twice.
- Decisions: #13, #14.

## M3 — Dispatch

Goal: ready orders get the nearest available courier, with reassignment. The dispatch module never reads `orders`; it keeps its own `dispatch_orders`.

**M3-1 Couriers and location.**
- Scope:
  - Migration `couriers`.
  - `POST /api/v1/couriers {name}` creates the courier OFFLINE with no location.
  - `PATCH /api/v1/couriers/{id}/status {status: AVAILABLE|OFFLINE}`. BUSY returns 400; no location gives 409 `COURIER_LOCATION_UNKNOWN`; AVAILABLE while BUSY gives 409 `COURIER_BUSY`.
  - `PUT /api/v1/couriers/{id}/location` writes Postgres every time. OFFLINE returns 409 `COURIER_OFFLINE`.
  - Redis geo key `couriers:available`: `GEOADD` when becoming AVAILABLE and on each update while AVAILABLE; `ZREM` on OFFLINE.
- Tests: ITs with Postgres + Redis containers for each rule.
- Decisions: #3, #12, #42.

**M3-2 Dispatch on READY.**
- Scope:
  - Migrations:
    - `dispatch_orders`: order_id PK, merchant name and address, pickup lat/lng, delivery address and lat/lng, `ready_at`, `courier_id`, status `WAITING` / `ASSIGNED` / `PICKED_UP` / `DONE`.
    - `offers`: status, `offered_at`, `expires_at`, `responded_at`.
    - Partial unique index `one_open_offer_per_order`.
  - The `OrderReady` listener inserts a `WAITING` row and dispatches.
  - Dispatching means:
    - `GEOSEARCH` 5 km nearest first, excluding couriers with an offer for this order in the last 5 min.
    - Claim the courier with `UPDATE couriers … WHERE status='AVAILABLE'` and `ZREM` it.
    - Insert an OFFERED offer expiring in 30 s.
  - A dispatch-again job every 10 s takes `WAITING` rows with no OFFERED offer (`SKIP LOCKED`, max 50).
- Tests:
  - TDD unit tests for candidate selection and exclusion.
  - ITs for two orders racing for one courier, the listener-vs-job race (unique index) and dispatching again.
- Decisions: #3, #11, #13, #14, #42.

**M3-3 Offer endpoints and accepting.**
- Scope:
  - `GET /api/v1/couriers/{id}/offers/current`: 200 `{offerId, orderId, merchantName, merchantAddress, pickupLat, pickupLng, deliveryAddress, deliveryLat, deliveryLng, expiresAt}` from `dispatch_orders`, or 204.
  - `POST …/offers/{offerId}/accept` and `decline`.
  - A late accept is blocked by `UPDATE … AND expires_at > now()` and returns 409 `OFFER_CLOSED`. Another courier's offer returns 404 `OFFER_NOT_FOUND`; a repeat returns 200 no-op.
  - Accept sets `dispatch_orders` to `ASSIGNED` and publishes `CourierAssigned`. The order-module listener sets `orders.courier_id`.
  - Decline sends the courier back to AVAILABLE (`GEOADD`) and dispatches again.
  - `dropoff_dispatch_duration_seconds` is computed later (M6-1) from `ready_at`.
- Tests: ITs for accept, decline, a late accept, the wrong courier and a repeat.
- Decisions: #11, #42.

**M3-4 Timeout, offline and reassignment.**
- Scope:
  - A timeout job every 5 s (`SKIP LOCKED`): the offer becomes TIMED_OUT, the courier OFFLINE, and the order is dispatched again.
  - OFFLINE with an open offer declines it.
  - OFFLINE while `ASSIGNED` publishes `CourierUnassigned`: `dispatch_orders` goes back to `WAITING`, and the order listener clears `courier_id`.
  - OFFLINE while `PICKED_UP` returns 409 `COURIER_HAS_ACTIVE_DELIVERY`.
- Tests: ITs for each path.
- Decisions: #3, #11, #14, #42.

**M3-5 Pickup and delivery.**
- Scope:
  - Courier `pick-up` / `deliver` commands in the order module (wrong courier: 404; deliver before pick-up: 409). They publish `OrderPickedUp` and `OrderDelivered`.
  - Dispatch listeners set `dispatch_orders` to `PICKED_UP`, then `DONE`. On delivery the courier becomes AVAILABLE (`GEOADD`).
- Tests: ITs for each path, including the OFFLINE-after-pickup 409.
- Decisions: #10, #11, #42.

**M3-6 Simulator: seed and bots.**
- Scope:
  - `tools/simulator`: Java 21, `HttpClient`, Jackson, no Spring. It runs with `./gradlew :tools:simulator:run --args=…`.
  - `seed` creates the Austin data through the API: 10 merchants × 8 items, 50 customers, 30 couriers.
  - `run` drives three kinds of bot:
    - Customer bots place 6 orders/min.
    - Merchant bots poll `GET /merchants/{id}/orders?status=PLACED` every 3 s, then step through after 5–20 s.
    - Courier bots poll offers every 2 s and answer 80/15/5 accept/decline/ignore. They travel at about 30 km/h in a straight line and send a location every 3 s.
  - A fixed random seed.
- Tests: unit tests for the movement and decision logic; a manual end-to-end run documented in the PR.
- Decisions: #33, #42.

## M4 — Live tracking

Goal: customers watch their order and courier move live.

**M4-1 STOMP status updates.**
- Scope:
  - STOMP over WebSocket at `/ws` (order module).
  - Every status change, including SYSTEM cancels, is sent as a `STATUS` message to `/topic/orders/{id}`.
- Tests: an IT with a STOMP client receiving each status.
- Decisions: #3, #13, #42.

**M4-2 Courier location stream.**
- Scope:
  - Dispatch publishes `CourierLocationUpdated` only while the courier's `dispatch_orders` row is `ASSIGNED` or `PICKED_UP`.
  - At most one per courier every 5 s (`SET courier:{id}:loc-pub 1 NX EX 5`).
  - A plain `@EventListener` (no registry). The order module sends it as a `LOCATION` message.
- Tests: an IT where updates at 0 s, 2 s and 6 s give 2 messages and 3 Postgres writes.
- Decisions: #12, #15, #42.

**M4-3 Simulator watch.**
- Scope: `simulator watch <orderId>` prints the `STATUS` and `LOCATION` messages.
- Tests: a manual check documented in the PR.
- Decisions: #33, #42.

## M5 — Kafka & service split

Goal: three services talking through durable events. Each PR leaves a working system.

**M5-1 Kafka inside the app.**
- Scope:
  - `apache/kafka:4.4.0` (KRaft) in Compose with `auto.create.topics.enable=false`.
  - `NewTopic` beans:
    - `order-events`, `courier-events` and `courier-locations` (key orderId; 7 days, 7 days and 1 hour).
    - `merchants` and `menu-items` (compacted).
    - `<topic>.DLT` (14 days).
    - All topics have 3 partitions and RF 1.
  - Producers: `spring-modulith-events-kafka` with `@Externalized("<topic>::#{orderId()}")`; `CourierLocationUpdated` sent with `KafkaTemplate`. JSON with logical type names.
  - Cross-module listeners become Kafka consumers in groups `order-service` and `dispatch-service`.
  - Migration `processed_events(consumer_group, event_id, processed_at, PK(consumer_group, event_id))`.
  - `DefaultErrorHandler`: 3 redelivery attempts (1/2/4 s), then the DLT.
  - `ops/kafka/replay-dlt.sh`.
- Tests: Testcontainers Kafka ITs:
  - an event is sent after commit and not after a rollback;
  - a duplicate is skipped;
  - a poison message ends in the DLT;
  - the whole order flow still passes.
- Decisions: #15, #32, #42, ADR-0006.

**M5-2 Contracts and web-common.**
- Scope:
  - Event records move to `contracts` (records only, no Spring, additive changes only).
  - `DomainException`, the advice and the paging limits move to `libs/web-common`.
  - Still one app.
- Tests: all existing tests stay green.
- Decisions: #31.

**M5-3 Order module uses merchant copies.**
- Scope:
  - The merchant module publishes `MerchantChanged` and `MenuItemChanged` to the compacted topics.
  - The order module fills `merchant_copies` (merchant_id, name, address, open, lat, lng) and `menu_item_copies` (menu_item_id, merchant_id, name, price_cents, available) from them, rebuilding by replay from offset 0 when empty.
  - Order validation and `OrderReady` fields now come from the copies.
  - Still one app.
- Tests: ITs for copy rebuild and validation from the copies.
- Decisions: #16, #42, ADR-0007.

**M5-4 Extract dispatch-service.**
- Scope:
  - `services/dispatch` on port 8083 with `dispatch_db`, owning `couriers`, `offers`, `dispatch_orders`, its own `event_publication` / `processed_events`, and Redis.
  - A Postgres init script creates the three databases and users.
  - Drop the FKs from dispatch tables to order tables.
  - The simulator gets a base URL per service.
- Tests: service ITs; `ops/smoke/full-flow.sh` (5 orders delivered within 5 min) run manually.
- Decisions: #16, #31, #42, ADR-0003, ADR-0007.

**M5-5 Extract merchant-service.**
- Scope:
  - `services/merchant` on port 8081 with `merchant_db` (`merchants`, `menu_items`).
  - Drop the FKs from `order_lines` and `orders` to merchant tables.
- Tests: service ITs; ordering works while merchant-service is stopped; smoke script.
- Decisions: #16, ADR-0007.

**M5-6 Remaining app becomes order-service.**
- Scope:
  - Rename `app/` to `services/order`: port 8082, `order_db`, WebSocket.
  - Each service keeps a Modulith verify test.
  - Fresh databases seeded by the simulator; no data migration.
- Tests: all service ITs; smoke script.
- Decisions: #16, #31, ADR-0007.

## M6 — Ops

Goal: containers, published images and dashboards.

**M6-1 Metrics.**
- Scope:
  - Actuator `/actuator/prometheus` and Prometheus v3.13.5 in Compose (15 s scrape).
  - All `dropoff_*` metrics from #34. Gauges come from one SQL query cached 15 s. `dropoff_dispatch_duration_seconds` is accept time minus `ready_at`.
  - `dropoff_dlt_messages_total{topic}`, incremented by each dead-letter recoverer.
  - Percentile histograms on `http_server_requests_seconds`.
- Tests: ITs asserting the metric names exist and the counters move.
- Decisions: #34, #42.

**M6-2 Grafana as code.**
- Scope:
  - `ops/grafana/provisioning/`: the datasource, the dashboard JSON (9 panels) and 3 alert rules:
    - oldest waiting order > 15 min;
    - `dropoff_dlt_messages_total` increase in 5 min;
    - p95 > 500 ms for 5 min.
  - Alerts show in the UI only.
- Tests: Compose starts Grafana with the dashboard loaded (manual check plus a screenshot in the PR).
- Decisions: #13, #34, #42.

**M6-3 Dockerfiles and the demo profile.**
- Scope:
  - One Dockerfile per service: layered jar extract on `eclipse-temurin:21-jre-alpine`, non-root user.
  - A Dockerfile for the simulator.
  - Compose runs the images; profile `demo` adds the simulator.
- Tests: CI builds each image and checks it is under 300 MB.
- Decisions: #33, #36, #42.

**M6-4 Publish to GHCR and the CI smoke test.**
- Scope:
  - After build and tests pass, a workflow pushes the public images `ghcr.io/danie1le/dropoff-<service>`: `sha-<short>` and `main` on main; `<version>` and `latest` on `v*` tags.
  - PRs build but don't push. linux/amd64; `GITHUB_TOKEN` with `packages: write`.
  - CI runs `ops/smoke/full-flow.sh` against Compose.
- Tests: the workflow run on main shows the pushed tags and a green smoke step.
- Decisions: #36, #42.

## M7 — Performance

Goal: measured load and one or two measured wins.

**M7-1 Load test harness.**
- Scope:
  - `ops/k6/` scripts:
    - `place-orders`: 5→50 orders/s over 10 min.
    - `browse`: 100 req/s.
    - `order-history`: deep pages.
  - Thresholds: p95 < 300 ms, errors < 1%.
  - Compose profile `load` (`grafana/k6:2.3.0`).
  - `ops/perf/seed-history.sql` (~200k orders).
  - `pg_stat_statements` enabled.
- Tests: each script runs to completion locally.
- Decisions: #35.

**M7-2 Baseline.**
- Scope:
  - The median of 3 runs per scenario: throughput, p50/p95/p99, errors, dispatch p95 and the top 5 queries.
  - Hardware and SHA recorded.
  - `docs/perf/README.md` started.
- Tests: none; this is a measurement PR.
- Decisions: #35.

**M7-3 and M7-4 Optimizations (one or two PRs).**
- Scope:
  - Pick from: index, cache, batching, offset to keyset paging, location writes to Redis.
  - Choose by the share of the worst p95 each explains (`pg_stat_statements` + `EXPLAIN (ANALYZE, BUFFERS)`).
  - The PR body carries the before/after table.
- Tests: existing tests stay green; numbers come from the same k6 harness.
- Decisions: #35.

**M7-5 Write-up.**
- Scope:
  - `docs/perf/README.md` complete: setup, baseline, each change with EXPLAIN, the after numbers and the % change.
  - A summary table in the README.
- Tests: none; this is a docs PR (links checked by review).
- Decisions: #35.

## M8 — Polish

Goal: a repo a reviewer can understand at a glance.

**M8-1 Swagger completeness.**
- Scope: every endpoint has a summary, examples and its error codes, in all three services.
- Tests: an IT per service that `/v3/api-docs` lists all endpoints.
- Decisions: #8, #42.

**M8-2 README.**
- Scope:
  - The 15 sections from #37.
  - A Mermaid architecture diagram.
  - A quick start: ports 8081/8082/8083, Swagger URLs, Grafana on 3000, the `demo` profile.
  - Known gaps: no auth, no payments.
- Tests: none; this is a docs PR (the quick start is followed once from a clean clone).
- Decisions: #37.

**M8-3 AI usage notes.**
- Scope: `docs/ai-usage.md` covers the tools, how output was checked (tests, code-review on every PR, user review, quizzes) and the mistakes caught. The README links to it.
- Tests: none; this is a docs PR.
- Decisions: #37.

**M8-4 Demo media.**
- Scope:
  - A demo GIF under 30 s and under 10 MB (simulator `watch` + Grafana, ScreenToGif).
  - Screenshots of Swagger, Grafana and the k6 summary in `docs/media/`.
- Tests: none; this is a docs PR.
- Decisions: #37.

---

## Out of scope

Authentication and authorization (a known gap noted in the README), payments and refunds, an API gateway, a maximum delivery distance, widening the dispatch radius, image vulnerability scanning, and the stretch goals (ETA, batching, surge pricing, Kotlin service, map UI, hosted deploy, broadcast offers, road-distance routing). See the map's Out of scope section.
