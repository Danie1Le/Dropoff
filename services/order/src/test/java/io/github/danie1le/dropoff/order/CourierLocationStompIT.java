package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.KafkaTestSupport;
import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.common.KafkaTopics;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

/**
 * A {@code CourierLocationUpdated} read from {@code courier-locations} reaches the order's STOMP topic as a {@code
 * LOCATION} message (M4-2, tickets #12, #15). Since M5-4 dispatch-service sends the event, and throttles it, from its
 * own process (see its {@code CourierLocationStreamIT}), so this test writes the record to Kafka itself, as dispatch
 * would. The listener reads no rows, so there is nothing to delete.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CourierLocationStompIT {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final SimpleBrokerMessageHandler broker;
    private final WebSocketStompClient client;
    private final StompSession session;

    CourierLocationStompIT(@Autowired SimpleBrokerMessageHandler broker, @LocalServerPort int port) throws Exception {
        this.broker = broker;
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        this.session = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @AfterEach
    void disconnect() {
        if (session.isConnected()) {
            session.disconnect();
        }
        client.stop();
    }

    /** An order id no other test uses, so no other test's message lands on its topic. */
    private static long anyOrderId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
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

    /** Writes the event to {@code courier-locations} as dispatch-service sends it: JSON, keyed by orderId. */
    private static void locationUpdated(long orderId, long courierId, double lat, double lng, Instant at) {
        byte[] json = ("{\"eventId\":\"%s\",\"courierId\":%d,\"orderId\":%d,\"lat\":%s,\"lng\":%s,"
                        + "\"at\":\"%s\",\"occurredAt\":\"%s\"}")
                .formatted(UUID.randomUUID(), courierId, orderId, lat, lng, at, at)
                .getBytes(StandardCharsets.UTF_8);
        KafkaTestSupport.send(KafkaTopics.COURIER_LOCATIONS, Long.toString(orderId), "CourierLocationUpdated", json);
    }

    @Test
    void aLocationEventReachesTheOrdersTopic() throws Exception {
        long orderId = anyOrderId();
        BlockingQueue<JsonNode> messages = subscribe(orderId);
        Instant at = Instant.parse("2026-11-08T12:00:00Z");

        locationUpdated(orderId, 7, 30.2680, -97.7440, at);

        JsonNode message = messages.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(message).as("a LOCATION message within %s", WAIT).isNotNull();
        assertThat(message.get("type").asString()).isEqualTo("LOCATION");
        assertThat(message.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(message.get("courierId").asLong()).isEqualTo(7);
        assertThat(message.get("lat").asDouble()).isEqualTo(30.2680);
        assertThat(message.get("lng").asDouble()).isEqualTo(-97.7440);
        assertThat(Instant.parse(message.get("at").asString())).isEqualTo(at);
    }
}
