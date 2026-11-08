package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** order-service starts on its own database, with its own Flyway history (M5-6, ADR-0007). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderServiceApplicationIT {

    private final Flyway flyway;
    private final JdbcClient jdbc;

    OrderServiceApplicationIT(@Autowired Flyway flyway, @Autowired JdbcClient jdbc) {
        this.flyway = flyway;
        this.jdbc = jdbc;
    }

    /**
     * Couriers, offers and dispatch_orders live in dispatch-service's database (M5-4), and merchants and menu_items in
     * merchant-service's (M5-5), so none of their tables exist here.
     */
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
                        "customers",
                        "orders",
                        "order_lines",
                        "order_events",
                        "merchant_copies",
                        "menu_item_copies",
                        "event_publication",
                        "processed_events");
    }

    /**
     * orders.courier_id, assigned_offer_id and merchant_id, and order_lines.menu_item_id, hold ids from other services'
     * databases that no FK checks (ADR-0003). Only customer_id and order_id have FKs.
     */
    @Test
    void foreignKeysStayInsideTheOrderTables() {
        assertThat(foreignKeyColumns("orders")).containsExactly("customer_id");
        assertThat(foreignKeyColumns("order_lines")).containsExactly("order_id");
        assertThat(foreignKeyColumns("order_events")).containsExactly("order_id");
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
