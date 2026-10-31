# Version matrix: Spring Boot 3.5 vs 4.x (ticket #5)

_Researched 2026-10-31. Every version below was checked against Maven Central metadata, the spring.io generations API, or GitHub release pages (links at the bottom)._

## Recommendation

**Use Spring Boot 4.1.x (4.1.2 today) on Java 21 with Gradle 9.8.2 (Kotlin DSL).** Don't use Spring Boot 3.

- **Boot 3.5.x free (OSS) support ended on 2026-06-30.** Only paid commercial support remains (until 2032-06-30). The last OSS release on Central is 3.5.16 (2026-06-25). A new project on 3.x would start out unpatched, which also goes against the free/OSS-only rule.
- **Boot 4.0.x** OSS support ends 2026-12-31, so it isn't worth starting on.
- **Boot 4.1.x** has OSS support until 2027-07-31. **Boot 4.2.0** is at RC1, with GA planned for about 2026-11-30. Start on 4.1.2 and move to 4.2.x once it's out, since that's a minor bump.
- Let the Boot BOM pick the versions for everything it manages: spring-kafka, Flyway, Micrometer, Testcontainers, Jackson and the Postgres driver. Only springdoc and Spring Modulith need explicit versions, so pin those two to the lines built for Boot 4.1.
- PROJECT_OVERVIEW.md says "Spring Boot 3". Change it to "Spring Boot 4".

## Version table (for Boot 4.1.2, Java 21)

| Component | Version | How you get it | Notes |
|---|---|---|---|
| Spring Boot (Gradle plugin `org.springframework.boot`) | **4.1.2** | explicit | Java 17 to 27 supported. Spring Framework 7.0.10, Jakarta EE 11 |
| Gradle wrapper | **9.8.2** | `gradle wrapper --gradle-version 9.8.2` | Boot 4.1 supports Gradle 8.14+ and 9.x |
| springdoc-openapi (`springdoc-openapi-starter-webmvc-ui`) | **3.2.0** | explicit | 3.x is for Boot 4 (2.x is for Boot 3). 3.2.0 is built on Boot 4.1.2 |
| Spring Modulith (`spring-modulith-bom`) | **2.1.2** | explicit BOM | Modulith 2.1.x goes with Boot 4.1.x. 2.1.2 depends on Boot 4.1.2 |
| spring-kafka | 4.1.2 | Boot BOM | Kafka client 4.2.2 (KRaft only, no ZooKeeper) |
| Flyway core and `org.flywaydb:flyway-database-postgresql` | 12.4.0 | Boot BOM | Flyway 13.x exists, but don't override Boot's managed version |
| Micrometer, incl. `micrometer-registry-prometheus` | 1.17.2 | Boot BOM | |
| Testcontainers (`testcontainers-bom`) | 2.0.5 | Boot BOM | Redis: `com.redis:testcontainers-redis` 2.2.4, also managed |
| Jackson | 3.1.7 (`tools.jackson`). Jackson 2 BOM 2.21.7 still managed | Boot BOM | Jackson 3 is the default |
| PostgreSQL JDBC | 42.7.14 | Boot BOM | |

Everything above is Apache-2.0 or another OSS license. Flyway's Postgres module is in the free Community edition.

## Gotchas when starting on Boot 4

**Starters are now split into modules (Boot 4 migration guide):**
- `spring-boot-starter-web` is deprecated. Use **`spring-boot-starter-webmvc`**.
- `spring-boot-starter-aop` is now `spring-boot-starter-aspectj`.
- `spring-boot-starter-oauth2-*` is now `spring-boot-starter-security-oauth2-*`.
- Each technology needs its own starter. A bare third-party jar no longer turns on its auto-configuration:
  - **`spring-boot-starter-flyway`** plus `flyway-database-postgresql`. Without the starter, migrations silently don't run.
  - **`spring-boot-starter-kafka`**, not just `spring-kafka`.
  - `spring-boot-starter-data-jpa`, `spring-boot-starter-data-redis`, `spring-boot-starter-actuator` and `spring-boot-starter-websocket`.
- Test starters exist per technology: `spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-flyway-test`, `-kafka-test`, and so on. Each one pulls in `spring-boot-starter-test`.
- `spring-boot-starter-classic` and `spring-boot-starter-test-classic` exist only to help migrations. Don't use them in a new project.

**Testing changes:**
- `@SpringBootTest` no longer auto-provides `MockMvc`, `TestRestTemplate` or `WebClient`. Add `@AutoConfigureMockMvc` or `@AutoConfigureRestTestClient` (for `RestTestClient`).
- `@MockBean` and `@SpyBean` are removed. Use `@MockitoBean` and `@MockitoSpyBean`.

**Jackson 3:**
- The group/package moved from `com.fasterxml.jackson` to `tools.jackson`. `jackson-annotations` is the exception and keeps `com.fasterxml.jackson`.
- To customize the mapper, define a `JsonMapper` bean or a `JsonMapperBuilderCustomizer`. An `ObjectMapper` bean no longer replaces the auto-configured mapper.
- `@JsonComponent` is now `@JacksonComponent`.
- `spring.jackson.read.*` and `spring.jackson.write.*` move to `spring.jackson.json.*`.
- Tutorials written for Boot 3 will have wrong imports.

**spring-kafka 4 (Jackson 3):**
- Use `JacksonJsonSerializer`, `JacksonJsonDeserializer` and `JacksonJsonSerde`. The old `JsonSerializer`, `JsonDeserializer` and `JsonSerde` are deprecated.
- Spring Retry is removed. In `@RetryableTopic`, use `backOff = @BackOff(delayString = "2s", ...)` instead of `@Backoff`.
- `EmbeddedKafkaZKBroker` is removed. Prefer the Testcontainers Kafka module anyway.

**Testcontainers 2.x:**
- Artifacts are renamed with a `testcontainers-` prefix, e.g. `org.testcontainers:testcontainers-postgresql`, `testcontainers-kafka` and `testcontainers-junit-jupiter` (`postgresql`, `kafka` and `junit-jupiter` are the old names).
- Container classes moved into module packages, e.g. `org.testcontainers.postgresql.PostgreSQLContainer`.
- JUnit 4 support is removed.

**Actuator:** Liveness and readiness health probes are now on by default (`management.endpoint.health.probes.enabled`).

## Sources

- Spring Boot support generations (OSS and commercial end dates): https://api.spring.io/projects/spring-boot/generations and https://spring.io/projects/spring-boot#support
- Spring Modulith generations (which Boot line each Modulith line goes with): https://api.spring.io/projects/spring-modulith/generations
- Spring Boot 4.1.2 system requirements (Java and Gradle): https://docs.spring.io/spring-boot/system-requirements.html
- Spring Boot 4.0 migration guide (starters, Jackson 3, testing): https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- Versions managed by the Boot 4.1.2 BOM: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom
- Boot release list (latest GA 4.1.2, 4.2.0-RC1, last 3.5.x is 3.5.16): https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml
- springdoc 3.2.0 release notes and compatibility table: https://github.com/springdoc/springdoc-openapi/releases/tag/v3.2.0 and https://github.com/springdoc/springdoc-openapi#compatibility
- Spring Modulith 2.1.2 (released 2026-10-23, depends on Boot 4.1.2): https://github.com/spring-projects/spring-modulith/releases and https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-starter-core/2.1.2/
- Testcontainers 2.0.0 release notes (renames, JUnit 4 removed): https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0
- Testcontainers latest release (2.0.5): https://repo1.maven.org/maven2/org/testcontainers/testcontainers/maven-metadata.xml
- What's new in spring-kafka 4.0: https://docs.spring.io/spring-kafka/reference/4.0/whats-new.html
- Flyway Postgres module: https://repo1.maven.org/maven2/org/flywaydb/flyway-database-postgresql/
- Current Gradle release (9.8.2, 2026-10-19): https://services.gradle.org/versions/current
