package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class OrderWatchTest {

    private static final String CONNECTED = "CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0";
    private static final String DISCONNECT = "DISCONNECT\n\n\0";
    private static final String CLOSE = "<close>";

    // Every frame sent, in order, with CLOSE where the transport was asked to close.
    private final List<String> sent = new ArrayList<>();
    private final List<String> printed = new ArrayList<>();
    // The test completes this to say the close frame went out.
    private final CompletableFuture<Void> closeSent = new CompletableFuture<>();
    private final FakeApi api = new FakeApi();
    private final OrderWatch watch = new OrderWatch(
            7,
            "localhost",
            new OrderWatch.Transport() {
                @Override
                public void send(String text) {
                    sent.add(text);
                }

                @Override
                public CompletableFuture<?> close() {
                    sent.add(CLOSE);
                    return closeSent;
                }
            },
            api,
            printed::add);

    private static String message(String json) {
        return "MESSAGE\ndestination:/topic/orders/7\nsubscription:sub-0\nmessage-id:m-1\n\n" + json + "\0";
    }

    private static String status(String status) {
        return message("{\"type\":\"STATUS\",\"orderId\":7,\"status\":\"" + status + "\",\"actor\":\"COURIER\","
                + "\"at\":\"2026-11-08T12:20:00Z\"}");
    }

    @Test
    void connectsThenSubscribesToTheOrderTopicOnceConnected() {
        api.orderStatuses.put(7L, "ACCEPTED");
        watch.start();

        assertThat(sent).containsExactly("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0");

        watch.receive(CONNECTED);

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo("SUBSCRIBE\nid:sub-0\ndestination:/topic/orders/7\n\n\0");
        assertThat(printed).containsExactly("Watching order 7. Ctrl+C to stop.", "Order 7 is ACCEPTED.");
        assertThat(api.calls).containsExactly("orderStatus 7");
        assertThat(watch.done()).isNotDone();
    }

    @Test
    void printsStatusAndLocationMessages() {
        api.orderStatuses.put(7L, "PLACED");
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
        api.orderStatuses.put(7L, "PLACED");
        watch.start();
        String frame = message("{\"type\":\"STATUS\",\"orderId\":7,\"status\":\"READY\",\"actor\":\"MERCHANT\","
                + "\"at\":\"2026-11-08T12:00:05Z\"}");

        watch.receive(CONNECTED + frame.substring(0, 20));
        watch.receive(frame.substring(20));

        assertThat(printed).contains("2026-11-08T12:00:05Z  STATUS    READY by MERCHANT");
    }

    @Test
    void stopsAfterTheOrderIsDelivered() {
        api.orderStatuses.put(7L, "PICKED_UP");
        watch.start();
        watch.receive(CONNECTED);

        watch.receive(status("DELIVERED"));
        closeSent.complete(null);

        assertThat(printed).last().isEqualTo("Order 7 is DELIVERED.");
        assertThat(sent).endsWith(DISCONNECT, CLOSE);
        assertThat(watch.done()).isCompleted();
    }

    @Test
    void isDoneOnlyAfterTheCloseFrameIsSent() {
        api.orderStatuses.put(7L, "PICKED_UP");
        watch.start();
        watch.receive(CONNECTED);

        watch.receive(status("CANCELLED"));

        assertThat(sent).endsWith(DISCONNECT, CLOSE);
        assertThat(watch.done()).isNotDone();

        closeSent.complete(null);

        assertThat(watch.done()).isCompleted();
    }

    @Test
    void theServerClosingAfterTheWatchStopsIsNotAFailure() {
        api.orderStatuses.put(7L, "PICKED_UP");
        watch.start();
        watch.receive(CONNECTED);
        watch.receive(status("DELIVERED"));

        watch.closed(1000, "");
        closeSent.complete(null);

        assertThat(printed).doesNotContain("Connection closed (1000 ).");
        assertThat(watch.done()).isCompleted();
    }

    @Test
    void exitsAtOnceWhenTheOrderIsAlreadyFinished() {
        api.orderStatuses.put(7L, "DELIVERED");
        watch.start();

        watch.receive(CONNECTED);
        closeSent.complete(null);

        assertThat(printed).containsExactly("Watching order 7. Ctrl+C to stop.", "Order 7 is DELIVERED.");
        assertThat(sent).endsWith(DISCONNECT, CLOSE);
        assertThat(watch.done()).isCompleted();
    }

    @Test
    void keepsWaitingWhenTheOrderDoesNotExistYet() {
        watch.start();

        watch.receive(CONNECTED);

        assertThat(printed).last().isEqualTo("Order 7 does not exist yet. Waiting for it.");
        assertThat(sent).doesNotContain(CLOSE);
        assertThat(watch.done()).isNotDone();

        watch.receive(status("CANCELLED"));
        closeSent.complete(null);

        assertThat(printed).last().isEqualTo("Order 7 is CANCELLED.");
        assertThat(watch.done()).isCompleted();
    }

    @Test
    void failsWhenTheOrderStatusCannotBeRead() {
        api.failOn = "orderStatus";
        api.failure = new ApiException(500, null);
        watch.start();

        watch.receive(CONNECTED);
        closeSent.complete(null);

        assertThat(printed).last().isEqualTo("Could not read order 7: HTTP 500");
        assertThat(sent).last().isEqualTo(CLOSE);
        assertThat(watch.done()).isCompletedExceptionally();
    }

    @Test
    void failsOnAnErrorFrame() {
        watch.start();

        watch.receive("ERROR\nmessage:Only /topic/orders/{id} can be subscribed to.\n\n\0");
        closeSent.complete(null);

        assertThat(printed).last().isEqualTo("Server error: Only /topic/orders/{id} can be subscribed to.");
        assertThat(sent).last().isEqualTo(CLOSE);
        assertThat(watch.done()).isCompletedExceptionally();
    }

    @Test
    void failsOnAFrameWithAnUndefinedEscape() {
        watch.start();

        watch.receive("ERROR\nmessage:bad\\tescape\n\n\0");
        closeSent.complete(null);

        assertThat(printed).last().isEqualTo("Bad STOMP frame: Undefined STOMP header escape \\t");
        assertThat(sent).last().isEqualTo(CLOSE);
        assertThat(watch.done()).isCompletedExceptionally();
    }
}
