package io.github.danie1le.dropoff.simulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Runs an {@link OrderWatch} over {@code java.net.http.WebSocket} against {@code ws://<host>/ws} (M4-3). This adapter
 * has no logic of its own to test: it passes text both ways and reports a close. The manual check is in the PR.
 */
final class WebSocketTransport implements OrderWatch.Transport, WebSocket.Listener {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    // RFC 6455 abnormal closure: the connection dropped without a close frame. The JDK has no constant for it.
    private static final int ABNORMAL_CLOSURE = 1006;

    private final OrderWatch watch;
    private @Nullable WebSocket socket;
    // The JDK allows one outstanding send at a time, so each send waits for the one before.
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);

    private WebSocketTransport(long orderId, URI baseUrl, DropoffApi api, Consumer<String> out) {
        this.watch = new OrderWatch(orderId, baseUrl.getHost(), this, api, out);
    }

    /** Connects to {@code baseUrl}'s {@code /ws}, starts the watch and returns it. */
    static OrderWatch open(long orderId, URI baseUrl, DropoffApi api, Consumer<String> out) {
        WebSocketTransport transport = new WebSocketTransport(orderId, baseUrl, api, out);
        // Take the socket from buildAsync: the JDK may call onOpen later, on another thread, after join() returns.
        WebSocket webSocket = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build()
                .newWebSocketBuilder()
                .buildAsync(wsUri(baseUrl), transport)
                .join();
        transport.attach(webSocket);
        transport.watch.start();
        return transport.watch;
    }

    /** {@code http://localhost:8080} becomes {@code ws://localhost:8080/ws}; {@code https} becomes {@code wss}. */
    static URI wsUri(URI baseUrl) {
        String base = baseUrl.toString().replaceFirst("^http", "ws");
        return URI.create((base.endsWith("/") ? base : base + "/") + "ws");
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        watch.receive(data);
        webSocket.request(1);
        return null;
    }

    @Override
    public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        watch.closed(statusCode, reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        watch.closed(ABNORMAL_CLOSURE, String.valueOf(error.getMessage()));
    }

    @Override
    public synchronized void send(String text) {
        WebSocket ws = connected();
        lastSend = lastSend.thenCompose(ignored -> ws.sendText(text, true));
    }

    @Override
    public synchronized CompletableFuture<?> close() {
        WebSocket ws = connected();
        lastSend = lastSend.thenCompose(ignored -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "watch done"));
        return lastSend;
    }

    private synchronized void attach(WebSocket webSocket) {
        this.socket = webSocket;
    }

    private synchronized WebSocket connected() {
        WebSocket ws = socket;
        if (ws == null) {
            throw new IllegalStateException("WebSocket is not open yet");
        }
        return ws;
    }
}
