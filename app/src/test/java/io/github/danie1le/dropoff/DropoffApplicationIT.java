package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DropoffApplicationIT {

    private final Flyway flyway;
    private final JdbcClient jdbc;

    DropoffApplicationIT(@Autowired Flyway flyway, @Autowired JdbcClient jdbc) {
        this.flyway = flyway;
        this.jdbc = jdbc;
    }

    @Test
    void startsAgainstRealPostgresWithEveryMigrationApplied() {
        MigrationInfo current = flyway.info().current();

        assertThat(current).isNotNull();
        assertThat(flyway.info().pending()).isEmpty();
    }

    /**
     * Couriers, offers and dispatch_orders moved to dispatch-service's database in M5-4 (V16), so their tables are gone
     * here, and orders.courier_id and assigned_offer_id hold ids that no FK checks (ADR-0003).
     */
    @Test
    void holdsNoDispatchTablesAndNoForeignKeyToThem() {
        assertThat(jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                        .query(String.class)
                        .list())
                .doesNotContain("couriers", "offers", "dispatch_orders");
        assertThat(jdbc.sql("SELECT kcu.column_name FROM information_schema.table_constraints tc"
                                + " JOIN information_schema.key_column_usage kcu"
                                + " ON tc.constraint_name = kcu.constraint_name"
                                + " WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = 'orders'")
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder("customer_id", "merchant_id");
    }
}
