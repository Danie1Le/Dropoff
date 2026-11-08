package io.github.danie1le.dropoff.order;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rows for order tests, written straight to the tables. One copy of each insert, so a schema change is one edit. Public
 * so tests in {@code order.internal} can use it too.
 */
public final class OrderFixtures {

    private OrderFixtures() {}

    public static long customer(JdbcClient jdbc) {
        return jdbc.sql("INSERT INTO customers (name, address, lat, lng) VALUES ('Alice', '200 Lavaca St', 30.2669,"
                        + " -97.7466) RETURNING id")
                .query(Long.class)
                .single();
    }

    public static long merchant(JdbcClient jdbc, boolean open) {
        return jdbc.sql("INSERT INTO merchants (name, address, lat, lng, is_open) VALUES ('Taco Shop', '100 Congress"
                        + " Ave', 30.2672, -97.7431, ?) RETURNING id")
                .param(open)
                .query(Long.class)
                .single();
    }

    public static long item(JdbcClient jdbc, long merchantId, long priceCents, boolean available) {
        return jdbc.sql("INSERT INTO menu_items (merchant_id, name, price_cents, is_available) VALUES (?, 'Taco', ?, ?)"
                        + " RETURNING id")
                .params(merchantId, priceCents, available)
                .query(Long.class)
                .single();
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
     * An ACCEPTED offer of the order to the courier, for the FK behind {@code orders.assigned_offer_id}. Adds dispatch's
     * copy of the order first if it has none. Each call returns a larger id than the last.
     */
    public static long acceptedOffer(JdbcClient jdbc, long orderId, long courierId) {
        jdbc.sql("INSERT INTO dispatch_orders (order_id, merchant_name, merchant_address, pickup_lat, pickup_lng,"
                        + " delivery_address, delivery_lat, delivery_lng, ready_at)"
                        + " VALUES (?, 'Taco Shop', '100 Congress Ave', 30.2672, -97.7431, '200 Lavaca St', 30.2669,"
                        + " -97.7466, now()) ON CONFLICT (order_id) DO NOTHING")
                .param(orderId)
                .update();
        return jdbc.sql("INSERT INTO offers (order_id, courier_id, status, offered_at, expires_at, responded_at)"
                        + " VALUES (?, ?, 'ACCEPTED', now(), now() + interval '30 seconds', now()) RETURNING id")
                .params(orderId, courierId)
                .query(Long.class)
                .single();
    }

    /**
     * Deletes the orders with their lines, events and dispatch rows. Clears {@code orders.assigned_offer_id} first, since
     * its FK to offers would otherwise block deleting them.
     */
    public static void deleteOrders(JdbcClient jdbc, List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE orders SET assigned_offer_id = NULL WHERE id IN (:ids)")
                .param("ids", orderIds)
                .update();
        for (String table : List.of("offers", "dispatch_orders", "order_events", "order_lines")) {
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
