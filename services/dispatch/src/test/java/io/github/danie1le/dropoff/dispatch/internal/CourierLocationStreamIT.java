package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.DispatchTestcontainers;
import io.github.danie1le.dropoff.KafkaTestSupport;
import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.dispatch.DispatchFixtures;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A delivering courier's location updates go out on {@code courier-locations} as {@code CourierLocationUpdated}, at
 * most one per courier every 5 s, keyed by orderId (M4-2, tickets #12, #15, #42). Every update is still saved. Redis
 * expires the throttle key on its own clock, so the test deletes the key to stand in for the 5 s passing instead of
 * sleeping. One order's records share a partition, so an update that should have been dropped would show up before
 * the next one that goes out. Not rolled back: the publish happens after commit, so each test deletes its rows and its
 * throttle keys. order-service's {@code CourierLocationStompIT} covers the push from the topic to the customer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DispatchTestcontainers.class)
class CourierLocationStreamIT {

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();

    CourierLocationStreamIT(
            @Autowired MockMvcTester mvc,
            @Autowired JdbcClient jdbc,
            @Autowired StringRedisTemplate redis,
            @Autowired JsonMapper json) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
    }

    @AfterEach
    void cleanUp() {
        courierIds.forEach(id -> redis.delete(CourierLocationStream.throttleKey(id)));
        DispatchFixtures.deleteOrders(jdbc, orderIds);
        if (!courierIds.isEmpty()) {
            jdbc.sql("DELETE FROM couriers WHERE id IN (:ids)")
                    .param("ids", courierIds)
                    .update();
        }
        orderIds.clear();
        courierIds.clear();
    }

    private record Delivery(long orderId, long courierId) {}

    /**
     * A BUSY courier and an order whose {@code dispatch_orders} row is in {@code dispatchStatus}, written straight to the
     * tables as the accept and pickup leave them.
     */
    private Delivery delivery(String dispatchStatus) {
        long courierId = jdbc.sql("INSERT INTO couriers (name, status, last_lat, last_lng)"
                        + " VALUES ('Carla', 'BUSY', 30.2672, -97.7431) RETURNING id")
                .query(Long.class)
                .single();
        courierIds.add(courierId);
        return delivery(courierId, dispatchStatus);
    }

    /** Another order for an existing courier, so one courier can hold several (ticket #85 take-back). */
    private Delivery delivery(long courierId, String dispatchStatus) {
        long orderId = DispatchFixtures.waitingOrder(jdbc);
        orderIds.add(orderId);
        setDispatchStatus(orderId, courierId, dispatchStatus);
        return new Delivery(orderId, courierId);
    }

    private void setDispatchStatus(long orderId, long courierId, String dispatchStatus) {
        jdbc.sql("UPDATE dispatch_orders SET status = ?, courier_id = ? WHERE order_id = ?")
                .params(dispatchStatus, courierId, orderId)
                .update();
    }

    /** Sends a location and checks it was saved: the courier reads back at exactly this point. */
    private void sendLocation(long courierId, double lat, double lng) {
        assertThat(mvc.put()
                        .uri("/api/v1/couriers/{id}/location", courierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":" + lat + ",\"lng\":" + lng + "}")
                        .exchange())
                .hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/couriers/{id}", courierId).exchange())
                .bodyJson()
                .satisfies(body -> {
                    assertThat(body).extractingPath("$.lat").isEqualTo(lat);
                    assertThat(body).extractingPath("$.lng").isEqualTo(lng);
                });
    }

    /**
     * Reads {@code courier-locations} until the update at {@code lat} for this order arrives, and returns every record
     * for the order up to it.
     */
    private List<JsonNode> locationsUpTo(long orderId, double lat) {
        String key = Long.toString(orderId);
        return KafkaTestSupport.readUntil(
                        KafkaTopics.COURIER_LOCATIONS,
                        record -> key.equals(record.key())
                                && parse(record).get("lat").asDouble() == lat)
                .stream()
                .filter(record -> key.equals(record.key()))
                .map(this::parse)
                .toList();
    }

    private JsonNode parse(ConsumerRecord<String, byte[]> record) {
        return json.readTree(record.value());
    }

    private static void assertLocation(JsonNode event, Delivery d, double lat, double lng) {
        assertThat(event.get("orderId").asLong()).isEqualTo(d.orderId());
        assertThat(event.get("courierId").asLong()).isEqualTo(d.courierId());
        assertThat(event.get("lat").asDouble()).isEqualTo(lat);
        assertThat(event.get("lng").asDouble()).isEqualTo(lng);
        assertThat(event.get("at").asString()).isNotBlank();
    }

    @Test
    void updatesAtZeroTwoAndSixSecondsGiveTwoEventsAndThreeWrites() {
        Delivery d = delivery("ASSIGNED");

        sendLocation(d.courierId(), 30.2680, -97.7440); // 0 s: published, opens the 5 s window
        sendLocation(d.courierId(), 30.2690, -97.7450); // 2 s: saved, inside the window, dropped
        redis.delete(CourierLocationStream.throttleKey(d.courierId())); // the 5 s window ends
        sendLocation(d.courierId(), 30.2700, -97.7460); // 6 s: published

        List<JsonNode> events = locationsUpTo(d.orderId(), 30.2700);
        assertThat(events).hasSize(2);
        assertLocation(events.get(0), d, 30.2680, -97.7440);
        assertLocation(events.get(1), d, 30.2700, -97.7460);
    }

    @Test
    void aPickedUpOrderStillGetsLocations() {
        Delivery d = delivery("PICKED_UP");

        sendLocation(d.courierId(), 30.2680, -97.7440);

        assertThat(locationsUpTo(d.orderId(), 30.2680))
                .singleElement()
                .satisfies(event -> assertLocation(event, d, 30.2680, -97.7440));
    }

    /** The courier's next order proves nothing went out for the delivered one before it. */
    @Test
    void aDeliveredOrderGetsNoLocations() {
        Delivery d = delivery("DONE");

        sendLocation(d.courierId(), 30.2680, -97.7440);
        setDispatchStatus(d.orderId(), d.courierId(), "ASSIGNED");
        redis.delete(CourierLocationStream.throttleKey(d.courierId()));
        sendLocation(d.courierId(), 30.2700, -97.7460);

        assertThat(locationsUpTo(d.orderId(), 30.2700))
                .singleElement()
                .satisfies(event -> assertLocation(event, d, 30.2700, -97.7460));
    }

    /**
     * A courier given a late order back (ticket #85) can hold a PICKED_UP and an ASSIGNED order at once. The update is
     * still saved, and goes out for both orders in the same throttle window.
     */
    @Test
    void aCourierWithTwoActiveOrdersPublishesForBoth() {
        Delivery first = delivery("PICKED_UP");
        Delivery second = delivery(first.courierId(), "ASSIGNED");

        sendLocation(first.courierId(), 30.2680, -97.7440);

        assertThat(locationsUpTo(first.orderId(), 30.2680))
                .singleElement()
                .satisfies(event -> assertLocation(event, first, 30.2680, -97.7440));
        assertThat(locationsUpTo(second.orderId(), 30.2680))
                .singleElement()
                .satisfies(event -> assertLocation(event, second, 30.2680, -97.7440));
    }
}
