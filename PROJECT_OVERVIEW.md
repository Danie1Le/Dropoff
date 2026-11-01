# Dropoff — Project Overview

> A real-time order and courier dispatch backend built with Java, Spring Boot, PostgreSQL, Redis, and Kafka.

## Summary

Dropoff is a backend for a three-sided delivery marketplace: **customers** place orders, **merchants** prepare them, and **couriers** deliver them. It starts as a modular monolith (one Spring Boot app) and splits into a small set of services that communicate through REST APIs and events, with real-time dispatch and live order tracking.

The goal is a portfolio project that demonstrates production-style backend engineering:

- clean, tested code
- SQL data modeling
- service-oriented architecture
- event-driven design
- real-time features
- CI/CD and monitoring
- measured performance improvements

Everything runs locally with Docker Compose at no cost.

### Target roles

Entry-level backend and software engineering roles at delivery and marketplace companies (DoorDash, Uber, Instacart), fintech, big tech, and any Java/Spring shop.

---

## Features

### Core (MVP)

- **Merchants and menus:** create merchants, menus, and items, and toggle item availability.
- **Orders:** customers place orders, and each order moves through a status lifecycle: `PLACED → ACCEPTED → PREPARING → READY → PICKED_UP → DELIVERED` (or `CANCELLED`).
- **Couriers:** register couriers, set their status (Offline, Available, Busy), and update their location.
- **Dispatch:** pick the nearest available courier for a ready order using Redis geo queries.
- **REST APIs:** documented with OpenAPI/Swagger.
- **Validation and error handling:** consistent error responses and input validation.

### Stand-out features

- **Event-driven flow:** services publish and consume Kafka events such as `OrderPlaced`, `OrderReady`, `CourierAssigned`, and `OrderDelivered`.
- **Live tracking:** WebSocket updates for order status and courier location.
- **Idempotency:** retry-safe order creation using idempotency keys.
- **Failure handling:** reassign an order if its courier declines, does not respond, or goes offline before pickup, and retry consumers with a dead-letter topic.
- **Load testing:** k6 scripts that simulate high order volume, with results in the README.
- **Performance writeup:** before-and-after measurements of an optimization such as an index, a cache, or batching.
- **Monitoring:** Prometheus metrics and a Grafana dashboard for request latency, orders per minute, and dispatch time.

### Stretch goals

- ETA estimation based on distance and merchant prep time.
- Batching multiple orders for one courier.
- Surge or dynamic delivery fee.
- One service rewritten in Kotlin.
- A simple frontend map view (React and Leaflet).
- A free hosted deploy, either on an Oracle Always Free VM via GitHub Actions or on the Render free tier.

---

## Architecture

```
                 ┌──────────────┐
  Client ──REST──▶ Order Service │──┐
     ▲           └──────┬───────┘  │
     │ WebSocket        │ SQL      │ Kafka events
     │           ┌──────▼───────┐  │
     │           │  PostgreSQL  │  │
     │           └──────────────┘  ▼
     │           ┌──────────────┐   ┌──────────────────┐
     └───────────│   Dispatch   │◀──│      Kafka       │
                 │   Service    │──▶│ (order/courier   │
                 └──────┬───────┘   │     topics)      │
                        │ GEO       └────────▲─────────┘
                 ┌──────▼───────┐            │
                 │    Redis     │   ┌────────┴─────────┐
                 └──────────────┘   │ Merchant Service │
                                    └──────────────────┘

        Prometheus ──scrapes──▶ all services ──▶ Grafana
```

### Services

| Service | Responsibility | Storage |
|---|---|---|
| **merchant-service** | Merchants, menus, items, availability | PostgreSQL |
| **order-service** | Order creation, status lifecycle, idempotency, WebSocket updates | PostgreSQL |
| **dispatch-service** | Courier status and location, dispatch to the nearest available courier, reassignment | Redis (geo) and PostgreSQL |

> Decision: start as a single Spring Boot app with clear packages per domain, then split it into services once the core works (see `docs/adr/0001-modular-monolith-first.md`).

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | **Java 21** (optional: Kotlin for one service) |
| Framework | **Spring Boot 4** (Web MVC, Data JPA, Validation, Actuator, WebSocket/STOMP) + **Spring Modulith** (module boundary checks) |
| Build | Gradle (Kotlin DSL) |
| Database | **PostgreSQL**, with Flyway for migrations |
| Cache / Geo | **Redis** (`GEOADD` / `GEOSEARCH`) |
| Messaging | **Kafka** in KRaft mode (Spring in-app events until the service split) |
| API Docs | springdoc-openapi (Swagger UI) |
| Testing | JUnit 5, Mockito, Testcontainers 2 |
| Load Testing | k6 |
| Containers | Docker and Docker Compose |
| CI/CD | **GitHub Actions** (test, build, publish images to GHCR) |
| Monitoring | Micrometer, Prometheus, and Grafana |
| Tools | IntelliJ IDEA Community, Postman/Bruno, Git |

**Cost: $0.** Everything is open source and runs locally.

---

## Data Model (rough)

```
merchants(id, name, address, lat, lng, is_open)
menu_items(id, merchant_id, name, price_cents, is_available)
customers(id, name, address, lat, lng)
couriers(id, name, status, last_lat, last_lng, updated_at)
orders(id, customer_id, merchant_id, courier_id, status, total_cents,
       idempotency_key UNIQUE, request_hash, created_at, updated_at)
order_lines(id, order_id, menu_item_id, quantity, price_cents)
order_events(id, order_id, from_status, to_status, created_at)
dispatch_attempts(id, order_id, courier_id, result, created_at)
processed_events(event_id UNIQUE, processed_at)
```

---

## API Sketch

```
POST   /merchants                       create merchant
POST   /merchants/{id}/items            add menu item
GET    /merchants/{id}/menu             get menu

POST   /orders            (Idempotency-Key header)   place order
GET    /orders/{id}                     get order + status
PATCH  /orders/{id}/status              advance status (merchant/courier)

POST   /couriers                        register courier
PATCH  /couriers/{id}/status            available / offline
PUT    /couriers/{id}/location          update location

WS     /ws  (STOMP)  SUBSCRIBE /topic/orders/{id}   live order + courier updates
```

---

## TODO

### Foundation
- [ ] Gradle and Spring Boot project setup
- [ ] Docker Compose with Postgres and Redis
- [ ] Flyway migrations for the schema
- [ ] Merchant and menu CRUD
- [ ] Order creation and status lifecycle
- [ ] Unit tests for services and status transitions
- [ ] GitHub Actions workflow to build and test on every push

### Dispatch
- [ ] Courier registration, status, and location endpoints
- [ ] Redis geo index for available couriers
- [ ] Dispatch to the nearest available courier when an order is `READY`
- [ ] Reassignment when a courier declines, does not respond, or goes offline before pickup
- [ ] Integration tests with Testcontainers

### Events and Real-time
- [ ] Add Kafka to Docker Compose
- [ ] Publish and consume order and courier events
- [ ] Retries and a dead-letter topic
- [ ] Idempotency keys on order creation
- [ ] WebSocket live tracking

### Ops
- [ ] Dockerfiles for each service
- [ ] CI builds and publishes images to GitHub Container Registry
- [ ] Actuator, Micrometer, and Prometheus metrics
- [ ] Grafana dashboard (provisioned in Compose)

### Performance
- [ ] k6 load test scripts
- [ ] Baseline measurements (throughput, p95 latency)
- [ ] One or two optimizations, measured before and after
- [ ] Write up the results in the README

### Polish
- [ ] README with architecture diagram, setup steps, and screenshots
- [ ] Swagger docs
- [ ] Short demo video or GIF
- [ ] Notes on how AI tools were used and how their output was checked
- [ ] Optional: a free hosted deploy
