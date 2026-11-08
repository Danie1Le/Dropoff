package io.github.danie1le.dropoff.order;

import io.github.danie1le.dropoff.Consumers;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rows for order tests, written straight to the tables. One copy of each insert, so a schema change is one edit. Public
 * so tests in {@code order.internal} can use it too.
 */
public final class OrderFixtures {

    /** Above the int range, so JSON always reads an id back as a long. */
    private static final long ID_BASE = 10_000_000_000L;

    /**
     * Courier and offer ids for orders. Couriers and offers live in dispatch-service since M5-4, with no FK from
     * {@code orders}, so any id will do; these count up from a random start so tests never share one.
     */
    private static final AtomicLong NEXT_ID = new AtomicLong(ID_BASE + System.nanoTime() % 1_000_000_000L * 1_000);

    private OrderFixtures() {}

    /** A courier id, as dispatch-service would assign one. */
    public static long courierId() {
        return NEXT_ID.incrementAndGet();
    }

    /** An offer id, as dispatch-service would assign one. Each call returns a larger id than the last. */
    public static long offerId() {
        return NEXT_ID.incrementAndGet();
    }

    public static long customer(JdbcClient jdbc) {
        return jdbc.sql("INSERT INTO customers (name, address, lat, lng) VALUES ('Alice', '200 Lavaca St', 30.2669,"
                        + " -97.7466) RETURNING id")
                .query(Long.class)
                .single();
    }

    /**
     * A merchant, plus the order module's copy of it, as the {@code MerchantChanged} consumer would write it (M5-3).
     * The merchants row is still needed for the foreign key from {@code orders}.
     */
    public static long merchant(JdbcClient jdbc, boolean open) {
        long id = jdbc.sql("INSERT INTO merchants (name, address, lat, lng, is_open) VALUES ('Taco Shop', '100 Congress"
                        + " Ave', 30.2672, -97.7431, ?) RETURNING id")
                .param(open)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO merchant_copies (merchant_id, name, address, open, lat, lng)"
                        + " SELECT id, name, address, is_open, lat, lng FROM merchants WHERE id = ?")
                .param(id)
                .update();
        return id;
    }

    /** A menu item, plus the order module's copy of it (M5-3); the menu_items row backs the FK from order lines. */
    public static long item(JdbcClient jdbc, long merchantId, long priceCents, boolean available) {
        long id = jdbc.sql("INSERT INTO menu_items (merchant_id, name, price_cents, is_available) VALUES (?, 'Taco', ?,"
                        + " ?) RETURNING id")
                .params(merchantId, priceCents, available)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO menu_item_copies (menu_item_id, merchant_id, name, price_cents, available)"
                        + " SELECT id, merchant_id, name, price_cents, is_available FROM menu_items WHERE id = ?")
                .param(id)
                .update();
        return id;
    }

    /** Deletes the merchants with their menu items, and the order module's copies of both. */
    public static void deleteMerchants(JdbcClient jdbc, List<Long> merchantIds) {
        if (merchantIds.isEmpty()) {
            return;
        }
        for (String table : List.of("menu_item_copies", "merchant_copies", "menu_items")) {
            jdbc.sql("DELETE FROM " + table + " WHERE merchant_id IN (:ids)")
                    .param("ids", merchantIds)
                    .update();
        }
        jdbc.sql("DELETE FROM merchants WHERE id IN (:ids)")
                .param("ids", merchantIds)
                .update();
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
