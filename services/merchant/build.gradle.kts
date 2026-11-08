// merchant-service (M5-5, ADR-0007): merchants and their menu items, on port 8081 with its own merchant_db, Flyway
// history and outbox. Every change goes out as MerchantChanged/MenuItemChanged on the compacted merchants and
// menu-items topics; order-service keeps its own copies, so ordering works while this service is down.
// Run with `./gradlew :services:merchant:bootRun` while Compose is up.
plugins {
    id("dropoff.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":libs:web-common"))
    implementation(project(":libs:service-common"))

    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jdbc)
    implementation(libs.spring.modulith.events.kafka)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.data.jpa)
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
