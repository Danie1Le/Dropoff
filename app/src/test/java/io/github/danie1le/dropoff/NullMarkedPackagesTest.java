package io.github.danie1le.dropoff;

import org.junit.jupiter.api.Test;

/** Applies the shared rule from service-common's test fixtures to this service. */
class NullMarkedPackagesTest {

    @Test
    void everyProductionPackageIsNullMarked() {
        NullMarkedPackages.check("io.github.danie1le.dropoff");
    }
}
