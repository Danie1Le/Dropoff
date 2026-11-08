package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
     * Couriers, offers and dispatch_orders moved to dispatch-service's database in M5-4 (V16), and merchants and
     * menu_items to merchant-service's in M5-5 (V17), so their tables are gone here. orders.courier_id,
     * assigned_offer_id and merchant_id, and order_lines.menu_item_id, hold ids that no FK checks (ADR-0003).
     */
    @Test
    void holdsNoDispatchOrMerchantTablesAndNoForeignKeyToThem() {
        assertThat(jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                        .query(String.class)
                        .list())
                .doesNotContain("couriers", "offers", "dispatch_orders", "merchants", "menu_items");
        assertThat(foreignKeyColumns("orders")).containsExactly("customer_id");
        assertThat(foreignKeyColumns("order_lines")).containsExactly("order_id");
    }

    private List<String> foreignKeyColumns(String table) {
        return jdbc.sql("SELECT kcu.column_name FROM information_schema.table_constraints tc"
                        + " JOIN information_schema.key_column_usage kcu"
                        + " ON tc.constraint_name = kcu.constraint_name"
                        + " WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ?")
                .param(table)
                .query(String.class)
                .list();
    }
}
