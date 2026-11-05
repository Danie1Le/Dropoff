package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DropoffApplicationIT {

    private final Flyway flyway;

    DropoffApplicationIT(@Autowired Flyway flyway) {
        this.flyway = flyway;
    }

    @Test
    void startsAgainstRealPostgresWithEveryMigrationApplied() {
        MigrationInfo current = flyway.info().current();

        assertThat(current).isNotNull();
        assertThat(flyway.info().pending()).isEmpty();
    }
}
