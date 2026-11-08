package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** dispatch-service starts on its own database, with its own Flyway history (M5-4, ADR-0007). */
@SpringBootTest
@Import(DispatchTestcontainers.class)
class DispatchServiceApplicationIT {

    private final Flyway flyway;
    private final JdbcClient jdbc;

    DispatchServiceApplicationIT(@Autowired Flyway flyway, @Autowired JdbcClient jdbc) {
        this.flyway = flyway;
        this.jdbc = jdbc;
    }

    @Test
    void startsWithItsBaselineAppliedAndOnlyItsOwnTables() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().pending()).isEmpty();

        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                        + " AND table_name <> 'flyway_schema_history'")
                .query(String.class)
                .list();
        assertThat(tables)
                .containsExactlyInAnyOrder(
                        "couriers", "dispatch_orders", "offers", "event_publication", "processed_events");
    }

    /** Order ids come from order-service's database, so no FK leaves dispatch's tables (ADR-0003). */
    @Test
    void noForeignKeyPointsOutsideTheDispatchTables() {
        List<String> targets = jdbc.sql("SELECT DISTINCT ccu.table_name FROM information_schema.table_constraints tc"
                        + " JOIN information_schema.constraint_column_usage ccu"
                        + " ON tc.constraint_name = ccu.constraint_name"
                        + " WHERE tc.constraint_type = 'FOREIGN KEY'")
                .query(String.class)
                .list();
        assertThat(targets).containsExactlyInAnyOrder("couriers", "dispatch_orders");
    }
}
