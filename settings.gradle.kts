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
// Demo data seeder and courier/merchant/customer bots (M3-6, ticket #33). Plain Java, no Spring.
include("tools:simulator")
