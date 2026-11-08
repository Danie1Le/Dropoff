package io.github.danie1le.dropoff.simulator;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code watch <orderId>} (M4-3, #42): a small STOMP client that subscribes to {@code /topic/orders/{id}} and prints
 * each {@code STATUS} and {@code LOCATION} message. It only reads: the app's {@code StompClientGuard} rejects any
 * client {@code SEND}. The socket sits behind {@link Transport}, so the protocol is unit-tested without a server.
 *
 * <p>Flow: {@code CONNECT}, wait for {@code CONNECTED}, {@code SUBSCRIBE}, read the current status from
 * {@code GET /orders/{id}} (the broker does not replay past messages), then print each {@code MESSAGE}. A
 * {@code DELIVERED} or {@code CANCELLED} status ends the watch with {@code DISCONNECT}; an {@code ERROR} frame, a bad
 * frame or a closed socket ends it with a failure. {@link #done()} completes only after the close frame is sent, so
 * the program does not exit before its last frames go out.
 */
final class OrderWatch {

    /** The socket under the watch: sends one text message, or closes. */
    interface Transport {
        void send(String text);

        /** Sends the close frame after every earlier send; the future completes once it is sent. */
        CompletableFuture<?> close();
    }

    private static final String SUBSCRIPTION_ID = "sub-0";
    private static final Set<String> TERMINAL = Set.of("DELIVERED", "CANCELLED");

    private final long orderId;
    private final String host;
    private final Transport transport;
    private final DropoffApi api;
    private final Consumer<String> out;
    private final JsonMapper json = JsonMapper.builder().build();
    private final StringBuilder buffer = new StringBuilder();
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    // Set once the watch decides to stop; done() completes later, when the close frame is sent.
    private boolean stopping;

    OrderWatch(long orderId, String host, Transport transport, DropoffApi api, Consumer<String> out) {
        this.orderId = orderId;
        this.host = host;
        this.transport = transport;
        this.api = api;
        this.out = out;
    }

    /**
     * Completes when the order is delivered or cancelled and the close frame is sent; fails on an {@code ERROR} frame,
     * a bad frame or a dropped socket.
     */
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
        while (!stopping && (end = buffer.indexOf(String.valueOf(StompFrame.NUL))) >= 0) {
            String text = buffer.substring(0, end + 1).stripLeading();
            buffer.delete(0, end + 1);
            StompFrame frame;
            try {
                frame = StompFrame.decode(text);
            } catch (IllegalArgumentException e) {
                out.accept("Bad STOMP frame: " + e.getMessage());
                fail("bad STOMP frame");
                return;
            }
            handle(frame);
        }
    }

    /** The socket closed. Before the order finished, that is a failure. */
    synchronized void closed(int statusCode, String reason) {
        if (!stopping) {
            stopping = true;
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
                checkCurrentStatus();
            }
            case "MESSAGE" -> print(json.readTree(frame.body()));
            case "ERROR" -> {
                out.accept("Server error: " + frame.headers().getOrDefault("message", frame.body()));
                fail("STOMP ERROR frame");
            }
            default -> {
                // RECEIPT and unknown commands carry nothing to print.
            }
        }
    }

    /**
     * Runs after {@code SUBSCRIBE}, so no later change is missed. A finished order ends the watch at once. A 404 keeps
     * the watch waiting, so it can be started before the order is placed.
     */
    private void checkCurrentStatus() {
        String status;
        try {
            status = api.orderStatus(orderId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                out.accept("Order " + orderId + " does not exist yet. Waiting for it.");
                return;
            }
            out.accept("Could not read order " + orderId + ": " + e.getMessage());
            fail("could not read the order status");
            return;
        }
        out.accept("Order " + orderId + " is " + status + ".");
        if (TERMINAL.contains(status)) {
            finish();
        }
    }

    private void print(JsonNode message) {
        String at = message.path("at").asString();
        switch (message.path("type").asString()) {
            case "STATUS" -> {
                String status = message.path("status").asString();
                out.accept(at + "  STATUS    " + status + " by "
                        + message.path("actor").asString());
                if (TERMINAL.contains(status)) {
                    out.accept("Order " + orderId + " is " + status + ".");
                    finish();
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

    /** Sends {@code DISCONNECT}, then the close frame; the watch is done once the close frame is sent. */
    private void finish() {
        stopping = true;
        transport.send(StompFrame.of("DISCONNECT").encode());
        CompletableFuture<?> unused = transport.close().whenComplete((ignored, error) -> done.complete(null));
    }

    /** Closes the socket; the watch fails once the close frame is sent. */
    private void fail(String reason) {
        stopping = true;
        CompletableFuture<?> unused = transport
                .close()
                .whenComplete((ignored, error) -> done.completeExceptionally(new IllegalStateException(reason)));
    }
}
