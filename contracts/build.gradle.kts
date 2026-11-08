// Event records shared by every module and, after the split, every service (M5-2, ADR-0008, decision #31).
// Records and enums only, additive changes only, and no Spring on the classpath: `main` has no dependencies at all,
// so a Spring import here fails to compile. ContractsPackageTest checks the rest.
plugins {
    id("dropoff.java-conventions")
    `java-library`
}

dependencies {
    // The Spring Boot BOM only pins test library versions here. It adds no jars.
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.platform.launcher)
}
