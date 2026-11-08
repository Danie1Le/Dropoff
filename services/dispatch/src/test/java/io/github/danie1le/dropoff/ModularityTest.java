package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(DispatchServiceApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    /** dispatch, plus the shared Gradle modules it builds on, checked like any other module (M5-2, M5-4). */
    @Test
    void seesItsModuleAndTheSharedGradleModules() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("dispatch", "common", "contracts", "web");
    }
}
