// Web building blocks every Dropoff service needs (M5-2, decision #31): DomainException, the Problem Details error
// handler and its Swagger docs, PageParams paging limits and the /api/v1 prefix. A plain library, not a Boot app: an
// app picks the handler and configs up by component scan, since they sit under io.github.danie1le.dropoff.
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("dropoff.java-conventions")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(platform(libs.spring.boot.bom))
    // PageParams is part of the API: it carries Jakarta constraints and returns a Spring Data Pageable.
    api(libs.spring.data.commons)
    api(libs.jakarta.validation.api)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.validation)
    testRuntimeOnly(libs.junit.platform.launcher)
    // PageParamsRule: the ArchUnit rule each service applies to its own controllers.
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.archunit)
    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesImplementation(libs.spring.boot.starter.webmvc)
}

// Test fixtures are test code: like compileTestJava, they are not null-checked.
tasks.named<JavaCompile>("compileTestFixturesJava") {
    options.errorprone.disable("NullAway")
}
