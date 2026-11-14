// Plumbing every Dropoff service shares (M5-4, decision #31): the Kafka setup (topics, routing, JSON with logical type
// names, retries and the DLT), consumer-side dedupe (ProcessedEvents), DbClock and the injectable Clock with the
// scheduling switch. Moved here from the app's `common` package instead of copied into each service: one copy of the
// topic list and the error handling keeps every service on the same wire format. A plain library like web-common:
// a service picks the configs up by component scan, since they sit under io.github.danie1le.dropoff.
//
// Its test fixtures hold the Testcontainers setup (Postgres and the shared Kafka broker) and KafkaTestSupport, so the
// ITs of every service start the same images, plus the ArchUnit rules each service applies to its own code.
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("dropoff.java-conventions")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.spring.modulith.bom))
    // kafka-clients pulls in snappy-java and lz4-java at versions with known advisories (IDs in the reasons below).
    // Declared on api so every service's runtime classpath gets the patched versions too.
    constraints {
        api(libs.snappy.java) { because("GHSA-gwq9-9ffj-mvrv, GHSA-q233-g92q-qxp6, GHSA-xqrx-qh46-34m7 (fixed in 1.1.10.9)") }
        api(libs.lz4.java) { because("GHSA-mcr4-qmvw-px4g, GHSA-343h-94h5-c4wr, GHSA-gm45-99xc-r7wv (fixed in 1.11.4), GHSA-48mj-8f7c-7qfm (fixed in 1.12.1)") }
    }
    implementation(project(":contracts"))
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.modulith.events.api)
    implementation(libs.jackson.databind)
    implementation(libs.micrometer.core)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesApi(libs.spring.boot.testcontainers)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.testcontainers.kafka)
    testFixturesApi(libs.archunit)
    testFixturesImplementation(libs.jakarta.validation.api)
    testFixturesImplementation(libs.jspecify)
    testFixturesImplementation(libs.spring.boot.starter.jdbc)
    testFixturesImplementation(libs.spring.boot.starter.kafka)
}

// Test fixtures are test code: like compileTestJava, they are not null-checked.
tasks.named<JavaCompile>("compileTestFixturesJava") {
    options.errorprone.disable("NullAway")
}
