// order-service (M5-6, ADR-0007): customers, orders and the live WebSocket (/ws), on port 8082 with its own order_db,
// Flyway history and outbox. It validates and prices orders from its own merchant and menu item copies, and follows
// couriers from dispatch-service's events. Run with `./gradlew :services:order:bootRun` while Compose is up.
plugins {
    id("dropoff.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    // Shared modules (M5-2): the event records and the web building blocks every service will use.
    implementation(project(":contracts"))
    implementation(project(":libs:web-common"))
    // Kafka setup, consumer dedupe and clocks, shared with dispatch-service since M5-4.
    implementation(project(":libs:service-common"))

    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    // /actuator/prometheus (M6-1, ticket #34)
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jdbc)
    implementation(libs.spring.modulith.events.kafka)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":libs:service-common")))
    testImplementation(testFixtures(project(":libs:web-common")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testRuntimeOnly(libs.junit.platform.launcher)
}
