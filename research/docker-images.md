# Free Docker images & licenses for Compose / Testcontainers (ticket #6)

Checked on 2026-10-31 against Docker Hub (registry API and official image pages), upstream docs and GitHub.
Constraint: free, local-only, Docker Desktop on Windows 11 (WSL2, amd64).

## Recommendation

| Service | Image:tag (pin this) | Upstream license | Why |
|---|---|---|---|
| PostgreSQL | `postgres:18.6-alpine` (or `postgres:18.6` for Debian trixie) | PostgreSQL License (permissive) | Docker Official Image, latest stable major (19 is still `19rc1`). Updated 2026-10-28. |
| Redis | `redis:8.10.3-alpine` | Redis 8+: RSALv2 / SSPLv1 / **AGPLv3** (your choice) | Docker Official Image, newest GA (= `8`, `alpine`, `latest`). AGPLv3 is an OSI license. Spring Boot `@ServiceConnection` recognises the image name `redis` automatically. GEOADD / GEOSEARCH are built in. |
| (alt) Valkey | `valkey/valkey:9.1.3-alpine` | BSD-3-Clause | Linux Foundation fork of Redis 7.2.4. Use it if you want a permissive license. Drop-in replacement, has GEOSEARCH, and adds `BYPOLYGON` in 9.0. Needs `@ServiceConnection(name = "redis")` in Spring tests. |
| Kafka (KRaft) | `apache/kafka:4.4.0` (optional: `apache/kafka-native:4.4.0`) | Apache-2.0 | Official ASF image. KRaft only (ZooKeeper was removed in 4.0). Released 2026-10-22. **`latest` is stale (= 4.3.1)**, so always pin the tag. |
| Prometheus | `prom/prometheus:v3.13.5` (LTS); newest is `v3.15.1` | Apache-2.0 | 3.13 is the only supported LTS (EOL 2027-07-31). |
| Grafana | `grafana/grafana:13.3.0` (= `latest`); `13.2.5` if you want a .x patch | AGPL-3.0 | Free OSS edition. Use `grafana/grafana`, **not** `grafana/grafana-enterprise`. |
| k6 | `grafana/k6:2.3.0` (1.x is still patched: `1.8.2`) | AGPL-3.0 | Official image. A run-once CLI container, not a long-running service. |

**Decision: use `redis:8.10.3-alpine`.** Licensing doesn't matter here because nothing is modified or offered as a hosted service. The official image name also gives zero-config Spring Boot test wiring. Valkey is a one-line swap if you want it.

**No Bitnami images.** On 2025-08-28 Bitnami cut `docker.io/bitnami` down to a small "latest-only" hardened subset. Everything else moved to `docker.io/bitnamilegacy`, which gets no updates. Versioned tags such as `bitnami/kafka:3.x`, `bitnami/redis:7.x` and `bitnami/postgresql:16` are gone or frozen ([bitnami/containers#83267](https://github.com/bitnami/containers/issues/83267)). Old tutorials that use `bitnami/kafka` with `KAFKA_CFG_*` env vars won't work. Use the `apache/kafka` snippet below instead.

### Architecture

Every image above publishes both `amd64` and `arm64`, so it works on Docker Desktop/WSL2 and on Apple Silicon. Exception: `postgis/postgis:18-3.6` is **amd64-only**. It's fine on Windows, but it would need emulation on an ARM Mac. That only matters if you choose PostGIS over Redis GEO.

## Redis license history (why it matters)

- Redis ≤ 7.2.4: BSD-3-Clause.
- Redis 7.4.x – 7.8.x (from March 2024): RSALv2 or SSPLv1 only. Neither is OSI open source. This is what triggered the Valkey fork.
- Redis 8.0+: tri-license, RSALv2 / SSPLv1 / AGPLv3. The Docker Hub page says the same ([redis.io/legal/licenses](https://redis.io/legal/licenses/)).
- Takeaway: don't pin `redis:7.4`. Use 8.x (AGPL option) or Valkey (BSD).

## GEO support

- `GEOADD` has been in Redis since 3.2. `GEOSEARCH` arrived in 6.2.0 and replaces the deprecated `GEORADIUS` / `GEORADIUSBYMEMBER` ([redis.io GEOSEARCH](https://redis.io/docs/latest/commands/geosearch/)). Both are core commands, with no module or Redis Stack needed, so `redis:8.10.3-alpine` has them.
- Valkey: GEOSEARCH since 6.2.0, `BYPOLYGON` added in 9.0.0 ([valkey.io GEOSEARCH](https://valkey.io/commands/geosearch/)).
- Spring Data Redis supports both through `GeoOperations.search(...)`.

## Kafka: minimal single-broker KRaft Compose

This is adapted from the ASF example `docker/examples/docker-compose-files/single-node/plaintext`. It has two listeners: `localhost:9092` for the app running on the host (IDE / `mvn spring-boot:run`), and `kafka:19092` for other containers on the Compose network.

```yaml
services:
  kafka:
    image: apache/kafka:4.4.0
    hostname: kafka
    container_name: kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: CONTROLLER://:29093,PLAINTEXT_HOST://:9092,PLAINTEXT://:19092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT_HOST://localhost:9092,PLAINTEXT://kafka:19092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:29093
      CLUSTER_ID: 4L6g3nShT-eMCtK--X86sw        # any base64 UUID; fixed so restarts reuse the log dir
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1  # Kafka default is 3 -> would fail on 1 broker
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_LOG_DIRS: /tmp/kraft-combined-logs   # upstream default; ephemeral (fine for dev)
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 10
```

`docker run apache/kafka:4.4.0` with no env vars already starts a working single-node KRaft broker on `localhost:9092` (Docker Hub quick start). The explicit config above is only needed for the dual host/network listeners.

### Testcontainers (Kafka)

- Artifact: `org.testcontainers:testcontainers-kafka:2.0.5`. In Testcontainers 2.x artifacts carry a `testcontainers-` prefix, so the old `org.testcontainers:kafka` artifact stops at 1.21.4.
- Class: **`org.testcontainers.kafka.KafkaContainer`**. It supports `apache/kafka` and `apache/kafka-native` and always runs KRaft.
- Don't use the deprecated `org.testcontainers.containers.KafkaContainer`, which runs `confluentinc/cp-kafka` with `withKraft()`. `org.testcontainers.kafka.ConfluentKafkaContainer` is for `cp-kafka` only.
- Spring Boot `@ServiceConnection` matches `org.testcontainers.kafka.KafkaContainer` by type.

```java
@Bean @ServiceConnection
KafkaContainer kafka() { return new KafkaContainer("apache/kafka:4.4.0"); }

@Bean @ServiceConnection
PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:18.6-alpine"); } // org.testcontainers.postgresql (testcontainers-postgresql:2.0.5)

@Bean @ServiceConnection   // image name "redis" is auto-detected
GenericContainer<?> redis() { return new GenericContainer<>("redis:8.10.3-alpine").withExposedPorts(6379); }
// Valkey: new GenericContainer<>("valkey/valkey:9.1.3-alpine").withExposedPorts(6379) + @ServiceConnection(name = "redis")
// Optional typed module: com.redis:testcontainers-redis:3.0.1 (RedisContainer), also matched by Spring Boot.
```

Prometheus, Grafana and k6 are Compose-only. They don't need Testcontainers.

## Gotchas

1. **Postgres 18 volume path changed.** `PGDATA` is now `/var/lib/postgresql/18/docker` and the `VOLUME` is `/var/lib/postgresql`. Mount `pgdata:/var/lib/postgresql`, **not** `/var/lib/postgresql/data` as in older tutorials ([docker-library/postgres#1259](https://github.com/docker-library/postgres/pull/1259)).
2. **`apache/kafka:latest` lags behind.** It points to 4.3.1 while 4.4.0 is out. Always pin.
3. **Kafka replication factors.** On a single broker, every `*_REPLICATION_FACTOR` / `*_MIN_ISR` must be 1, or internal topics (consumer offsets, transactions, share groups) fail to create.
4. **Advertised listeners.** The host app must use `localhost:9092`. Containers must use `kafka:19092`. If you mix them up, you get "connection to node 1 could not be established".
5. **Grafana:** use `grafana/grafana`. Its OSS build is AGPL. `grafana-enterprise` runs free without a license key, but it isn't OSS.
6. **k6 v2 vs v1:** v2.x is current (major release 2026-05). Use v2 for new scripts. 1.8.x is still patched.
7. **Redis Stack images** (`redis/redis-stack`) aren't needed. Redis 8 bundles JSON/Search/TimeSeries/Bloom in the main image, and GEO is core anyway.
8. **Kafka persistence:** the image runs as non-root `appuser`. If you mount a named volume at a path that doesn't exist in the image, Docker creates it root-owned and the broker can't write to it. Keep the ephemeral upstream log dir, or `chown` the volume first.
9. **Windows/WSL2:** keep Compose bind-mounted config files (`prometheus.yml`, Grafana provisioning) inside the repo and use named volumes for data. Bind-mounting data dirs from NTFS is slow and can cause permission errors (notably Postgres).

## Sources

- Docker Hub tags/digests via `https://hub.docker.com/v2/repositories/<repo>/tags`: [postgres](https://hub.docker.com/_/postgres), [redis](https://hub.docker.com/_/redis), [valkey/valkey](https://hub.docker.com/r/valkey/valkey), [apache/kafka](https://hub.docker.com/r/apache/kafka), [prom/prometheus](https://hub.docker.com/r/prom/prometheus), [grafana/grafana](https://hub.docker.com/r/grafana/grafana), [grafana/k6](https://hub.docker.com/r/grafana/k6)
- Redis licensing: https://redis.io/legal/licenses/ and the "License" section of https://hub.docker.com/_/redis
- Bitnami change: https://github.com/bitnami/containers/issues/83267
- GEOSEARCH: https://redis.io/docs/latest/commands/geosearch/ and https://valkey.io/commands/geosearch/
- Kafka Compose examples: https://github.com/apache/kafka/tree/trunk/docker/examples (single-node/plaintext), image usage guide https://github.com/apache/kafka/blob/trunk/docker/examples/README.md
- Testcontainers Kafka module: https://java.testcontainers.org/modules/kafka/ (Maven Central: `testcontainers-kafka` 2.0.5)
- Spring Boot service connections: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- Prometheus LTS: https://prometheus.io/docs/introduction/release-cycle/
- Postgres 18 PGDATA change: "PGDATA" section of https://hub.docker.com/_/postgres
