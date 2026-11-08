package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(MerchantServiceApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    /** merchant, plus the shared Gradle modules it builds on, checked like any other module (M5-2, M5-5). */
    @Test
    void seesItsModuleAndTheSharedGradleModules() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("merchant", "common", "contracts", "web");
    }
}
