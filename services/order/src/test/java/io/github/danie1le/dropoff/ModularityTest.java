package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(OrderServiceApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    /**
     * {@code contracts} and {@code web} live in their own Gradle modules since M5-2. Modulith still finds them on the
     * classpath, so dependencies on them are checked like any other module's, not skipped as library code.
     */
    @Test
    void seesTheSharedGradleModules() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .contains("contracts", "web");
    }
}
