/**
 * Events modules send each other (docs/PLAN.md, "Shared contracts"; ADR-0008). Records and enums only: no Spring, no
 * dependency on any other module, and additive changes only ({@code ContractsPackageTest} checks the first two). Every
 * module may depend on it, so a publisher and its listeners never depend on each other. Since M5-2 it is its own Gradle
 * module, {@code contracts}, with no Spring on its classpath.
 */
@NullMarked
package io.github.danie1le.dropoff.contracts;

import org.jspecify.annotations.NullMarked;
