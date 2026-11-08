// Loads the convention plugin (and with it Spotless and Error Prone) once at the root, so `app` and
// `tools:simulator` share one class loader for Spotless' shared build service (M3-6).
plugins {
    id("dropoff.java-conventions") apply false
}
