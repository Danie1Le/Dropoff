// Loads the convention plugin (and with it Spotless and Error Prone) once at the root, so every subproject
// (`app`, `contracts`, `libs:web-common`, `tools:simulator`) shares one class loader for Spotless' shared build service
// (M3-6).
plugins {
    id("dropoff.java-conventions") apply false
}
