// Shared build rules for every Java subproject (tickets #7 and #31).
// Subprojects apply this with `plugins { id("dropoff.java-conventions") }`.

import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

// Precompiled script plugins cannot use the generated `libs` accessor, so look the catalog up by name.
val libs = the<VersionCatalogsExtension>().named("libs")
fun version(alias: String): String = libs.findVersion(alias).get().requiredVersion
fun library(alias: String) = libs.findLibrary(alias).get()

group = "io.github.danie1le.dropoff"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    errorprone(library("errorprone-core"))
    errorprone(library("nullaway"))
    compileOnly(library("jspecify"))
    testCompileOnly(library("jspecify"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Spring binds @PathVariable and @RequestParam by parameter name. The Spring Boot plugin adds this flag to Boot
    // apps only, so library modules such as libs/web-common (M5-2) need it here too.
    if ("-parameters" !in options.compilerArgs) {
        options.compilerArgs.add("-parameters")
    }
    options.errorprone {
        // ERROR-level checks fail the build; warnings print and are reviewed (ticket #7).
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:CustomContractAnnotations", "org.springframework.lang.Contract")
    }
}

// Test code is not @NullMarked; null checks apply to production code only.
tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.disable("NullAway")
}

spotless {
    java {
        palantirJavaFormat(version("palantir-java-format"))
        removeUnusedImports()
        forbidWildcardImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// `test` runs unit tests (*Test); `integrationTest` runs Testcontainers tests (*IT). Ticket #7.
tasks.named<Test>("test") {
    useJUnitPlatform()
    exclude("**/*IT.class")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (*IT)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(integrationTest)
}

// JaCoCo report only; no coverage gate (ticket #7).
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}
