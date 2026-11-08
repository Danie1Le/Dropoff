package io.github.danie1le.dropoff.dispatch;

import io.github.danie1le.dropoff.Consumers;
import io.github.danie1le.dropoff.KafkaTestSupport;
import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.contracts.OrderReady;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rows and events for dispatch-service tests. Orders live in order-service's database since M5-4, so a test order is
 * only an id plus dispatch's own {@code dispatch_orders} row, and the order side shows up as Kafka records: events the
 * test sends on {@code order-events}, and {@code CourierAssigned} / {@code CourierUnassigned} it reads back from
 * {@code courier-events}. Public so tests in {@code dispatch.internal} can use it too.
 */
public final class DispatchFixtures {

    /** The merchant every test order is picked up from. */
    public static final double SHOP_LAT = 30.2672;

    public static final double SHOP_LNG = -97.7431;

    /** Above the int range, so JSON always reads an id back as a long. */
    private static final long ID_BASE = 10_000_000_000L;

    /** Order ids count up from a random start, so tests never share one, nor reuse one left on the shared broker. */
    private static final AtomicLong NEXT_ORDER_ID =
            new AtomicLong(ID_BASE + System.nanoTime() % 1_000_000_000L * 1_000);

    private DispatchFixtures() {}

    /** An order id order-service could have assigned, with no rows yet. */
    public static long orderId() {
        return NEXT_ORDER_ID.incrementAndGet();
    }

    /** A READY order in {@code dispatch_orders} as WAITING, as the {@code OrderReady} consumer leaves it. */
    public static long waitingOrder(JdbcClient jdbc) {
        long orderId = orderId();
        jdbc.sql("INSERT INTO dispatch_orders (order_id, merchant_name, merchant_address, pickup_lat, pickup_lng,"
                        + " delivery_address, delivery_lat, delivery_lng, ready_at)"
                        + " VALUES (?, 'Taco Shop', '100 Congress Ave', ?, ?, '200 Lavaca St', 30.2669, -97.7466,"
                        + " now())")
                .params(orderId, SHOP_LAT, SHOP_LNG)
                .update();
        return orderId;
    }

    /** {@code OrderReady} for an order at the test shop, as order-service publishes it. */
    public static OrderReady ready(long orderId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new OrderReady(
                UUID.randomUUID(),
                orderId,
                1,
                "Taco Shop",
                "100 Congress Ave",
                SHOP_LAT,
                SHOP_LNG,
                "200 Lavaca St",
                30.2669,
                -97.7466,
                now,
                now);
    }

    /** Sends an order event to {@code order-events} as order-service's outbox does: JSON, keyed by orderId. */
    public static void sendOrderEvent(JsonMapper json, long orderId, Record event) {
        KafkaTestSupport.send(
                KafkaTopics.ORDER_EVENTS,
                Long.toString(orderId),
                event.getClass().getSimpleName(),
                json.writeValueAsBytes(event));
    }

    /**
     * Waits for a {@code CourierAssigned} or {@code CourierUnassigned} (by {@code type}) for this order and courier on
     * {@code courier-events}, and returns its JSON.
     */
    public static String awaitCourierEvent(String type, long orderId, long courierId) {
        List<ConsumerRecord<String, byte[]>> read = KafkaTestSupport.readUntil(
                KafkaTopics.COURIER_EVENTS,
                record -> isCourierEvent(record, type, orderId)
                        && KafkaTestSupport.text(record).contains("\"courierId\":" + courierId + ","));
        return KafkaTestSupport.text(read.getLast());
    }

    /** Every {@code type} event read for this order from {@code courier-events}, oldest first, as JSON. */
    public static List<String> courierEvents(String type, long orderId) {
        return KafkaTestSupport.readAll(KafkaTopics.COURIER_EVENTS).stream()
                .filter(record -> isCourierEvent(record, type, orderId))
                .map(KafkaTestSupport::text)
                .toList();
    }

    private static boolean isCourierEvent(ConsumerRecord<String, byte[]> record, String type, long orderId) {
        return Long.toString(orderId).equals(record.key())
                && type.equals(KafkaTestSupport.header(record, "__TypeId__"));
    }

    /**
     * Deletes the orders' offers and dispatch rows. First waits until every event is sent and consumed, so no consumer
     * writes a row for these orders after the deletes.
     */
    public static void deleteOrders(JdbcClient jdbc, List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return;
        }
        Consumers.awaitSettled(jdbc);
        for (String table : List.of("offers", "dispatch_orders")) {
            jdbc.sql("DELETE FROM " + table + " WHERE order_id IN (:ids)")
                    .param("ids", orderIds)
                    .update();
        }
    }
}
