package io.github.danie1le.dropoff.order;

import io.github.danie1le.dropoff.Consumers;
import io.github.danie1le.dropoff.TestIds;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rows for order tests, written straight to the tables. One copy of each insert, so a schema change is one edit. Public
 * so tests in {@code order.internal} can use it too.
 */
public final class OrderFixtures {

    private OrderFixtures() {}

    /**
     * A courier id, as dispatch-service would assign one. Couriers and offers live in dispatch-service since M5-4,
     * with no FK from {@code orders}, so any id will do.
     */
    public static long courierId() {
        return TestIds.next();
    }

    /** An offer id, as dispatch-service would assign one. Each call returns a larger id than the last. */
    public static long offerId() {
        return TestIds.next();
    }

    public static long customer(JdbcClient jdbc) {
        return jdbc.sql("INSERT INTO customers (name, address, lat, lng) VALUES ('Alice', '200 Lavaca St', 30.2669,"
                        + " -97.7466) RETURNING id")
                .query(Long.class)
                .single();
    }

    /**
     * The order module's copy of a merchant, as the {@code MerchantChanged} consumer would write it (M5-3). Merchants
     * live in merchant-service since M5-5, with no FK from {@code orders}, so the copy is all the order side has.
     */
    public static long merchant(JdbcClient jdbc, boolean open) {
        long id = TestIds.next();
        jdbc.sql("INSERT INTO merchant_copies (merchant_id, name, address, open, lat, lng)"
                        + " VALUES (?, 'Taco Shop', '100 Congress Ave', ?, 30.2672, -97.7431)")
                .params(id, open)
                .update();
        return id;
    }

    /** The order module's copy of a menu item (M5-3). No FK from order lines since M5-5. */
    public static long item(JdbcClient jdbc, long merchantId, long priceCents, boolean available) {
        long id = TestIds.next();
        jdbc.sql("INSERT INTO menu_item_copies (menu_item_id, merchant_id, name, price_cents, available)"
                        + " VALUES (?, ?, 'Taco', ?, ?)")
                .params(id, merchantId, priceCents, available)
                .update();
        return id;
    }

    /** Deletes the order module's copies of the merchants and their menu items. */
    public static void deleteMerchants(JdbcClient jdbc, List<Long> merchantIds) {
        if (merchantIds.isEmpty()) {
            return;
        }
        for (String table : List.of("menu_item_copies", "merchant_copies")) {
            jdbc.sql("DELETE FROM " + table + " WHERE merchant_id IN (:ids)")
                    .param("ids", merchantIds)
                    .update();
        }
    }

    /** An order with no lines, in {@code status}, placed at {@code createdAt} (ISO-8601). */
    public static long order(
            JdbcClient jdbc, long customerId, long merchantId, String status, long totalCents, String createdAt) {
        return jdbc.sql("INSERT INTO orders (customer_id, merchant_id, status, total_cents, delivery_address,"
                        + " delivery_lat, delivery_lng, idempotency_key, request_hash, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, '200 Lavaca St', 30.2669, -97.7466, gen_random_uuid()::text,"
                        + " repeat('a', 64), ?::timestamptz, ?::timestamptz) RETURNING id")
                .params(customerId, merchantId, status, totalCents, createdAt, createdAt)
                .query(Long.class)
                .single();
    }

    /**
     * Deletes the orders with their lines and events. First waits until every Kafka event is consumed, so no consumer
     * writes a row for these orders after the deletes.
     */
    public static void deleteOrders(JdbcClient jdbc, List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return;
        }
        Consumers.awaitSettled(jdbc);
        for (String table : List.of("order_events", "order_lines")) {
            jdbc.sql("DELETE FROM " + table + " WHERE order_id IN (:ids)")
                    .param("ids", orderIds)
                    .update();
        }
        jdbc.sql("DELETE FROM orders WHERE id IN (:ids)").param("ids", orderIds).update();
    }

    /** One line of quantity 1 at {@code priceCents}. */
    public static void line(JdbcClient jdbc, long orderId, long menuItemId, long priceCents) {
        jdbc.sql("INSERT INTO order_lines (order_id, menu_item_id, quantity, price_cents) VALUES (?, ?, 1, ?)")
                .params(orderId, menuItemId, priceCents)
                .update();
    }
}
