package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

/**
 * A delivering courier's location updates reach the order's STOMP topic as {@code LOCATION} messages, at most one per
 * courier every 5 s (M4-2, tickets #12, #15, #42). Every update is still saved. Redis expires the throttle key on its
 * own clock, so the test deletes the key to stand in for the 5 s passing instead of sleeping. Not rolled back: the
 * push happens after commit, so each test deletes its rows and its throttle keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CourierLocationStompIT {

    private static final Duration WAIT = Duration.ofSeconds(5);
    /** How long to wait for a message that must not come. */
    private static final Duration QUIET = Duration.ofMillis(500);

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final SimpleBrokerMessageHandler broker;
    private final WebSocketStompClient client;
    private final StompSession session;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();
    private final List<Long> customerIds = new ArrayList<>();
    private final List<Long> merchantIds = new ArrayList<>();

    CourierLocationStompIT(
            @Autowired MockMvcTester mvc,
            @Autowired JdbcClient jdbc,
            @Autowired StringRedisTemplate redis,
            @Autowired SimpleBrokerMessageHandler broker,
            @LocalServerPort int port)
            throws Exception {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.redis = redis;
        this.broker = broker;
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        this.session = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @AfterEach
    void cleanUp() {
        if (session.isConnected()) {
            session.disconnect();
        }
        client.stop();
        courierIds.forEach(id -> redis.delete(throttleKey(id)));
        OrderFixtures.deleteOrders(jdbc, orderIds);
        deleteByIds("couriers", courierIds);
        deleteByIds("customers", customerIds);
        deleteByIds("merchants", merchantIds);
    }

    private void deleteByIds(String table, List<Long> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("DELETE FROM " + table + " WHERE id IN (:ids)")
                    .param("ids", ids)
                    .update();
        }
    }

    private static String throttleKey(long courierId) {
        return "courier:" + courierId + ":loc-pub";
    }

    private record Delivery(long orderId, long courierId) {}

    /**
     * A BUSY courier and an order whose {@code dispatch_orders} row is in {@code dispatchStatus}, written straight to the
     * tables as the accept and pickup leave them.
     */
    private Delivery delivery(String dispatchStatus) {
        long customerId = OrderFixtures.customer(jdbc);
        customerIds.add(customerId);
        long merchantId = OrderFixtures.merchant(jdbc, true);
        merchantIds.add(merchantId);
        long orderId = OrderFixtures.order(
                jdbc, customerId, merchantId, "READY", 399, Instant.now().toString());
        orderIds.add(orderId);
        long courierId = jdbc.sql("INSERT INTO couriers (name, status, last_lat, last_lng)"
                        + " VALUES ('Carla', 'BUSY', 30.2672, -97.7431) RETURNING id")
                .query(Long.class)
                .single();
        courierIds.add(courierId);
        OrderFixtures.acceptedOffer(jdbc, orderId, courierId);
        jdbc.sql("UPDATE dispatch_orders SET status = ?, courier_id = ? WHERE order_id = ?")
                .params(dispatchStatus, courierId, orderId)
                .update();
        return new Delivery(orderId, courierId);
    }

    /** Subscribes to the order's topic and waits until the broker has registered the subscription. */
    private BlockingQueue<JsonNode> subscribe(long orderId) {
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        String destination = "/topic/orders/" + orderId;
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                messages.add((JsonNode) payload);
            }
        });
        SimpMessageHeaderAccessor probe = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        probe.setDestination(destination);
        Awaitility.await()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(10))
                .atMost(WAIT)
                .until(() -> !broker.getSubscriptionRegistry()
                        .findSubscriptions(MessageBuilder.createMessage(new byte[0], probe.getMessageHeaders()))
                        .isEmpty());
        return messages;
    }

    /** Sends a location and checks it was saved: the courier reads back at exactly this point. */
    private void sendLocation(long courierId, double lat, double lng) throws Exception {
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

    private static JsonNode next(BlockingQueue<JsonNode> messages) throws InterruptedException {
        JsonNode message = messages.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(message).as("a LOCATION message within %s", WAIT).isNotNull();
        return message;
    }

    private static void assertLocation(JsonNode message, Delivery d, double lat, double lng) {
        assertThat(message.get("type").asString()).isEqualTo("LOCATION");
        assertThat(message.get("orderId").asLong()).isEqualTo(d.orderId());
        assertThat(message.get("courierId").asLong()).isEqualTo(d.courierId());
        assertThat(message.get("lat").asDouble()).isEqualTo(lat);
        assertThat(message.get("lng").asDouble()).isEqualTo(lng);
        assertThat(Instant.parse(message.get("at").asString())).isNotNull();
    }

    @Test
    void updatesAtZeroTwoAndSixSecondsGiveTwoMessagesAndThreeWrites() throws Exception {
        Delivery d = delivery("ASSIGNED");
        BlockingQueue<JsonNode> messages = subscribe(d.orderId());

        sendLocation(d.courierId(), 30.2680, -97.7440); // 0 s: pushed, opens the 5 s window
        sendLocation(d.courierId(), 30.2690, -97.7450); // 2 s: saved, inside the window, dropped
        redis.delete(throttleKey(d.courierId())); // the 5 s window ends
        sendLocation(d.courierId(), 30.2700, -97.7460); // 6 s: pushed

        assertLocation(next(messages), d, 30.2680, -97.7440);
        assertLocation(next(messages), d, 30.2700, -97.7460);
        assertThat(messages.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS))
                .as("no third LOCATION message")
                .isNull();
    }

    @Test
    void aPickedUpOrderStillGetsLocations() throws Exception {
        Delivery d = delivery("PICKED_UP");
        BlockingQueue<JsonNode> messages = subscribe(d.orderId());

        sendLocation(d.courierId(), 30.2680, -97.7440);

        assertLocation(next(messages), d, 30.2680, -97.7440);
    }

    @Test
    void aDeliveredOrderGetsNoLocationsAndKeepsNoWindowOpen() throws Exception {
        Delivery d = delivery("DONE");
        BlockingQueue<JsonNode> messages = subscribe(d.orderId());

        sendLocation(d.courierId(), 30.2680, -97.7440);

        assertThat(messages.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS)).isNull();
        assertThat(redis.hasKey(throttleKey(d.courierId()))).isFalse();
    }
}
