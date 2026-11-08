package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderWatchTest {

    private static final String CONNECTED = "CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0";

    private final List<String> sent = new ArrayList<>();
    private final List<String> printed = new ArrayList<>();
    private boolean closed;
    private final OrderWatch watch = new OrderWatch(
            7,
            "localhost",
            new OrderWatch.Transport() {
                @Override
                public void send(String text) {
                    sent.add(text);
                }

                @Override
                public void close() {
                    closed = true;
                }
            },
            printed::add);

    private static String message(String json) {
        return "MESSAGE\ndestination:/topic/orders/7\nsubscription:sub-0\nmessage-id:m-1\n\n" + json + "\0";
    }

    @Test
    void connectsThenSubscribesToTheOrderTopicOnceConnected() {
        watch.start();

        assertThat(sent).containsExactly("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0");

        watch.receive(CONNECTED);

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo("SUBSCRIBE\nid:sub-0\ndestination:/topic/orders/7\n\n\0");
        assertThat(printed).containsExactly("Watching order 7. Ctrl+C to stop.");
    }

    @Test
    void printsStatusAndLocationMessages() {
        watch.start();
        watch.receive(CONNECTED);

        watch.receive(message("{\"type\":\"STATUS\",\"orderId\":7,\"status\":\"ACCEPTED\",\"actor\":\"MERCHANT\","
                + "\"at\":\"2026-11-08T12:00:05Z\"}"));
        watch.receive(message("{\"type\":\"LOCATION\",\"orderId\":7,\"courierId\":3,\"lat\":30.2671234,"
                + "\"lng\":-97.7431,\"at\":\"2026-11-08T12:00:08Z\"}"));

        assertThat(printed)
                .containsSubsequence(
                        "2026-11-08T12:00:05Z  STATUS    ACCEPTED by MERCHANT",
                        "2026-11-08T12:00:08Z  LOCATION  courier 3 at 30.26712, -97.74310");
        assertThat(watch.done()).isNotDone();
    }

    @Test
    void joinsAFrameSplitOverSeveralWebSocketMessages() {
        watch.start();
        String frame = message("{\"type\":\"STATUS\",\"orderId\":7,\"status\":\"READY\",\"actor\":\"MERCHANT\","
                + "\"at\":\"2026-11-08T12:00:05Z\"}");

        watch.receive(CONNECTED + frame.substring(0, 20));
        watch.receive(frame.substring(20));

        assertThat(printed).contains("2026-11-08T12:00:05Z  STATUS    READY by MERCHANT");
    }

    @Test
    void stopsAfterTheOrderIsDelivered() {
        watch.start();
        watch.receive(CONNECTED);

        watch.receive(message("{\"type\":\"STATUS\",\"orderId\":7,\"status\":\"DELIVERED\",\"actor\":\"COURIER\","
                + "\"at\":\"2026-11-08T12:20:00Z\"}"));

        assertThat(printed).last().isEqualTo("Order 7 is DELIVERED.");
        assertThat(sent).last().isEqualTo("DISCONNECT\n\n\0");
        assertThat(closed).isTrue();
        assertThat(watch.done()).isCompleted();
    }

    @Test
    void failsOnAnErrorFrame() {
        watch.start();

        watch.receive("ERROR\nmessage:Only /topic/orders/{id} can be subscribed to.\n\n\0");

        assertThat(printed).last().isEqualTo("Server error: Only /topic/orders/{id} can be subscribed to.");
        assertThat(closed).isTrue();
        assertThat(watch.done()).isCompletedExceptionally();
    }
}
