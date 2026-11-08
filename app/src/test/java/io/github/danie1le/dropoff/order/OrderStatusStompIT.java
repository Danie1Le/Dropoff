package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.order.OrderCommandApiIT.Fixture;
import io.github.danie1le.dropoff.order.internal.AutoCancelJob;
import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
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
 * A STOMP client on {@code /ws} receives every status change of an order on {@code /topic/orders/{id}} (M4-1, #13,
 * #42). Runs on a real port, since STOMP needs a real WebSocket; the commands still go through MockMvc. Not rolled
 * back: messages are sent only after commit, so each test deletes its rows instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderStatusStompIT {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final AutoCancelJob autoCancelJob;
    private final SimpleBrokerMessageHandler broker;
    private final WebSocketStompClient client;
    private final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
    private final StompSession session;

    OrderStatusStompIT(
            @Autowired MockMvcTester mvc,
            @Autowired JdbcClient jdbc,
            @Autowired AutoCancelJob autoCancelJob,
            @Autowired SimpleBrokerMessageHandler broker,
            @LocalServerPort int port)
            throws Exception {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.autoCancelJob = autoCancelJob;
        this.broker = broker;
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        this.session = client.connectAsync("ws://localhost:" + port + "/ws", new ErrorCollector())
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @AfterEach
    void cleanUp() {
        // The rejection tests wait until the server has closed the session, so this check never races it.
        if (session.isConnected()) {
            session.disconnect();
        }
        client.stop();
        OrderCommandApiIT.deleteAll(jdbc);
        jdbc.sql("DELETE FROM couriers").update();
    }

    /** Collects ERROR frames, the server's answer to a frame it rejects. */
    private final class ErrorCollector extends StompSessionHandlerAdapter {
        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, @Nullable Object payload) {
            errors.add(headers.getFirst("message") + "");
        }
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
        // SUBSCRIBE has no reply from the simple broker, so poll its registry instead of guessing a delay.
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

    private static JsonNode next(BlockingQueue<JsonNode> messages) throws InterruptedException {
        JsonNode message = messages.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(message).as("a STATUS message within %s", WAIT).isNotNull();
        return message;
    }

    /** The {@code status/actor} pairs of the next {@code count} messages, after checking each one's shape. */
    private List<String> next(BlockingQueue<JsonNode> messages, long orderId, int count) throws InterruptedException {
        List<String> received = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JsonNode message = next(messages);
            assertThat(message.get("type").asString()).isEqualTo("STATUS");
            assertThat(message.get("orderId").asLong()).isEqualTo(orderId);
            String status = message.get("status").asString();
            assertThat(Instant.parse(message.get("at").asString())).isEqualTo(eventTime(orderId, status));
            received.add(status + "/" + message.get("actor").asString());
        }
        return received;
    }

    /** When the {@code order_events} row for the order's move to {@code status} was written. */
    private Instant eventTime(long orderId, String status) {
        return jdbc.sql("SELECT created_at FROM order_events WHERE order_id = ? AND to_status = ?")
                .params(orderId, status)
                .query(Timestamp.class)
                .single()
                .toInstant();
    }

    private void post(String uri, Object... vars) {
        assertThat(mvc.post().uri(uri, vars).exchange()).hasStatus2xxSuccessful();
    }

    @Test
    void aNewOrderIsPushedAsPlaced() throws Exception {
        // A client normally learns the id from the 201, after PLACED is sent; subscribe to the next id first instead.
        // Safe because ITs run one at a time.
        long nextId = jdbc.sql(
                        "SELECT COALESCE(pg_sequence_last_value(pg_get_serial_sequence('orders', 'id')::regclass),"
                                + " 0) + 1")
                .query(Long.class)
                .single();
        BlockingQueue<JsonNode> messages = subscribe(nextId);

        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);

        assertThat(f.orderId()).isEqualTo(nextId);
        assertThat(next(messages, f.orderId(), 1)).containsExactly("PLACED/CUSTOMER");
    }

    @Test
    void everyStatusFromAcceptedToDeliveredIsPushedInOrder() throws Exception {
        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        BlockingQueue<JsonNode> messages = subscribe(f.orderId());

        post("/api/v1/merchants/{m}/orders/{o}/accept", f.merchantId(), f.orderId());
        post("/api/v1/merchants/{m}/orders/{o}/start-preparing", f.merchantId(), f.orderId());
        post("/api/v1/merchants/{m}/orders/{o}/ready", f.merchantId(), f.orderId());
        // The courier is written straight to the row, as the CourierAssigned listener leaves it.
        long courierId = jdbc.sql("INSERT INTO couriers (name, status) VALUES ('Carla', 'BUSY') RETURNING id")
                .query(Long.class)
                .single();
        jdbc.sql("UPDATE orders SET courier_id = ? WHERE id = ?")
                .params(courierId, f.orderId())
                .update();
        post("/api/v1/couriers/{c}/orders/{o}/pick-up", courierId, f.orderId());
        post("/api/v1/couriers/{c}/orders/{o}/deliver", courierId, f.orderId());

        assertThat(next(messages, f.orderId(), 5))
                .containsExactly(
                        "ACCEPTED/MERCHANT",
                        "PREPARING/MERCHANT",
                        "READY/MERCHANT",
                        "PICKED_UP/COURIER",
                        "DELIVERED/COURIER");
    }

    @Test
    void aRepeatedCommandPushesNothing() throws Exception {
        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        BlockingQueue<JsonNode> messages = subscribe(f.orderId());

        post("/api/v1/merchants/{m}/orders/{o}/accept", f.merchantId(), f.orderId());
        post("/api/v1/merchants/{m}/orders/{o}/accept", f.merchantId(), f.orderId());
        post("/api/v1/merchants/{m}/orders/{o}/start-preparing", f.merchantId(), f.orderId());

        assertThat(next(messages, f.orderId(), 2)).containsExactly("ACCEPTED/MERCHANT", "PREPARING/MERCHANT");
    }

    @Test
    void aCustomerCancelIsPushed() throws Exception {
        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        BlockingQueue<JsonNode> messages = subscribe(f.orderId());

        post("/api/v1/customers/{c}/orders/{o}/cancel", f.customerId(), f.orderId());

        assertThat(next(messages, f.orderId(), 1)).containsExactly("CANCELLED/CUSTOMER");
    }

    @Test
    void aSystemCancelIsPushed() throws Exception {
        long orderId = OrderFixtures.order(
                jdbc,
                OrderFixtures.customer(jdbc),
                OrderFixtures.merchant(jdbc, true),
                "PLACED",
                399,
                Instant.now().minus(Duration.ofHours(1)).toString());
        BlockingQueue<JsonNode> messages = subscribe(orderId);

        autoCancelJob.run();

        assertThat(next(messages, orderId, 1)).containsExactly("CANCELLED/SYSTEM");
    }

    @Test
    void aClientCannotSendToAnOrdersTopic() throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/topic/orders/1");
        session.send(headers, Map.of("type", "STATUS", "orderId", 1, "status", "DELIVERED"));

        assertThat(errors.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).contains("SEND");
        awaitClosedByServer();
    }

    @Test
    void aClientCannotSubscribeToEveryOrder() throws Exception {
        session.subscribe("/topic/orders/**", new StompSessionHandlerAdapter() {});

        assertThat(errors.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).contains("/topic/orders/{id}");
        awaitClosedByServer();
    }

    /** STOMP ends the connection after an ERROR frame; Spring closes it from the server side. */
    private void awaitClosedByServer() {
        Awaitility.await()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(10))
                .atMost(WAIT)
                .until(() -> !session.isConnected());
    }
}
