package io.github.danie1le.dropoff.simulator;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code watch <orderId>} (M4-3, #42): a small STOMP client that subscribes to {@code /topic/orders/{id}} and prints
 * each {@code STATUS} and {@code LOCATION} message. It only reads: the app's {@code StompClientGuard} rejects any
 * client {@code SEND}. The socket sits behind {@link Transport}, so the protocol is unit-tested without a server.
 *
 * <p>Flow: {@code CONNECT}, wait for {@code CONNECTED}, {@code SUBSCRIBE}, then print each {@code MESSAGE}. A
 * {@code DELIVERED} or {@code CANCELLED} status ends the watch with {@code DISCONNECT}; an {@code ERROR} frame or a
 * closed socket ends it with a failure.
 */
final class OrderWatch {

    /** The socket under the watch: sends one text message, or closes. */
    interface Transport {
        void send(String text);

        void close();
    }

    private static final String SUBSCRIPTION_ID = "sub-0";
    private static final char NUL = '\0';

    private final long orderId;
    private final String host;
    private final Transport transport;
    private final Consumer<String> out;
    private final JsonMapper json = JsonMapper.builder().build();
    private final StringBuilder buffer = new StringBuilder();
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    OrderWatch(long orderId, String host, Transport transport, Consumer<String> out) {
        this.orderId = orderId;
        this.host = host;
        this.transport = transport;
        this.out = out;
    }

    /** Completes when the order is delivered or cancelled; fails on an {@code ERROR} frame or a dropped socket. */
    CompletableFuture<Void> done() {
        return done;
    }

    /** Sends {@code CONNECT}. Heart-beats are off ({@code 0,0}): the watch is short and runs on localhost. */
    void start() {
        transport.send(StompFrame.of("CONNECT", "accept-version", "1.2", "host", host, "heart-beat", "0,0")
                .encode());
    }

    /**
     * Takes one WebSocket text message, or part of one. STOMP frames end in NUL, so the text is
     * buffered until each NUL and handled frame by frame; the end-of-line bytes between frames are skipped.
     */
    synchronized void receive(CharSequence data) {
        buffer.append(data);
        int end;
        while (!done.isDone() && (end = buffer.indexOf(String.valueOf(NUL))) >= 0) {
            String text = buffer.substring(0, end + 1).stripLeading();
            buffer.delete(0, end + 1);
            handle(StompFrame.decode(text));
        }
    }

    /** The socket closed. Before the order finished, that is a failure. */
    void closed(int statusCode, String reason) {
        if (!done.isDone()) {
            out.accept("Connection closed (" + statusCode + " " + reason + ").");
            done.completeExceptionally(new IllegalStateException("connection closed before the order finished"));
        }
    }

    private void handle(StompFrame frame) {
        switch (frame.command()) {
            case "CONNECTED" -> {
                transport.send(
                        StompFrame.of("SUBSCRIBE", "id", SUBSCRIPTION_ID, "destination", "/topic/orders/" + orderId)
                                .encode());
                out.accept("Watching order " + orderId + ". Ctrl+C to stop.");
            }
            case "MESSAGE" -> print(json.readTree(frame.body()));
            case "ERROR" -> {
                out.accept("Server error: " + frame.headers().getOrDefault("message", frame.body()));
                transport.close();
                done.completeExceptionally(new IllegalStateException("STOMP ERROR frame"));
            }
            default -> {
                // RECEIPT and unknown commands carry nothing to print.
            }
        }
    }

    private void print(JsonNode message) {
        String at = message.path("at").asString();
        switch (message.path("type").asString()) {
            case "STATUS" -> {
                String status = message.path("status").asString();
                out.accept(at + "  STATUS    " + status + " by "
                        + message.path("actor").asString());
                if (status.equals("DELIVERED") || status.equals("CANCELLED")) {
                    out.accept("Order " + orderId + " is " + status + ".");
                    transport.send(StompFrame.of("DISCONNECT").encode());
                    transport.close();
                    done.complete(null);
                }
            }
            case "LOCATION" ->
                out.accept(String.format(
                        Locale.ROOT,
                        "%s  LOCATION  courier %d at %.5f, %.5f",
                        at,
                        message.path("courierId").asLong(),
                        message.path("lat").asDouble(),
                        message.path("lng").asDouble()));
            default -> out.accept(at + "  " + message);
        }
    }
}
