pluginManagement {
    // Convention plugins live in an included build so every subproject shares one set of build rules.
    includeBuild("build-logic")
}

plugins {
    // Downloads a matching JDK when the machine has no Java 21 (ticket #7).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // One place for repositories; subprojects may not declare their own.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "dropoff"

include("app")
// Event records every module and, after the split, every service shares (M5-2, ADR-0008). No Spring on its classpath.
include("contracts")
// DomainException, Problem Details error handling, paging limits and the /api/v1 prefix for every service (M5-2).
include("libs:web-common")
// Kafka setup, consumer dedupe and clocks every service shares, plus their Testcontainers test fixtures (M5-4).
include("libs:service-common")
// dispatch-service on port 8083 with its own dispatch_db (M5-4, ADR-0007): couriers, offers and dispatch_orders.
include("services:dispatch")
// merchant-service on port 8081 with its own merchant_db (M5-5, ADR-0007): merchants and menu items.
include("services:merchant")
// Demo data seeder and courier/merchant/customer bots (M3-6, ticket #33). Plain Java, no Spring.
include("tools:simulator")
