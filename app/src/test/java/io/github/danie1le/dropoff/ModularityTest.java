package io.github.danie1le.dropoff;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(DropoffApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }
}
