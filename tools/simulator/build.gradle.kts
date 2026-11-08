// Demo data seeder and bots (M3-6, ticket #33): a plain Java 21 CLI with HttpClient and Jackson, no Spring.
// Run with `./gradlew :tools:simulator:run --args="seed"`, then `--args="run"`, while the app is running.
plugins {
    id("dropoff.java-conventions")
    application
}

dependencies {
    // The Spring Boot BOM only pins versions here, so Jackson, SLF4J and JUnit match the app. No Spring code is used.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "io.github.danie1le.dropoff.simulator.Simulator"
}

// Run from the repository root, so the state file is always `<repo>/.simulator/state.json` (git-ignored).
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
