pluginManagement {
    // Convention plugins live in an included build so every subproject shares one set of build rules.
    includeBuild("build-logic")
}

plugins {
    // Downloads a matching JDK when the machine has no Java 21 (ticket #7).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "dropoff"

include("app")
