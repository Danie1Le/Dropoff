package io.github.danie1le.dropoff.order.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The guard's limits on a real {@code /ws} socket (M4-1). The client writes STOMP frames by hand, like the
 * simulator, so a test can close the socket without a {@code DISCONNECT} frame and can split one frame across two
 * WebSocket messages. Lives in the internal package to read the guard's {@code trackedSessions()}. Same setup as
 * {@code OrderStatusStompIT}, so the two share one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StompClientGuardIT {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String CONNECT = "CONNECT\naccept-version:1.2\nhost:localhost\n\n\0";

    private final StompClientGuard guard;
    private final String url;
    private final List<WebSocketSession> sessions = new ArrayList<>();

    StompClientGuardIT(@Autowired StompClientGuard guard, @LocalServerPort int port) {
        this.guard = guard;
        this.url = "ws://localhost:" + port + "/ws";
    }

    @AfterEach
    void closeSockets() throws Exception {
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    /** Collects the server's frames and the close status. */
    private static final class RawClient extends TextWebSocketHandler {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        String nextFrame() throws InterruptedException {
            String frame = frames.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(frame).as("a frame from the server within %s", WAIT).isNotNull();
            return frame;
        }
    }

    /** Opens a socket and completes the STOMP handshake. */
    private WebSocketSession connect(RawClient client) throws Exception {
        WebSocketSession session =
                new StandardWebSocketClient().execute(client, url).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        sessions.add(session);
        session.sendMessage(new TextMessage(CONNECT));
        assertThat(client.nextFrame()).startsWith("CONNECTED");
        return session;
    }

    private void awaitTrackedSessions(int expected) {
        Awaitility.await()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(10))
                .atMost(WAIT)
                .until(() -> guard.trackedSessions() == expected);
    }

    @Test
    void aSocketDroppedWithoutDisconnectIsForgotten() throws Exception {
        // Sessions from earlier tests are cleared asynchronously; start from a clean map.
        awaitTrackedSessions(0);
        RawClient client = new RawClient();
        WebSocketSession session = connect(client);
        session.sendMessage(new TextMessage("SUBSCRIBE\nid:sub-0\ndestination:/topic/orders/1\n\n\0"));
        awaitTrackedSessions(1);

        // A WebSocket close with no STOMP DISCONNECT frame first, as when a browser tab or network goes away.
        session.close();

        awaitTrackedSessions(0);
    }

    @Test
    void aFrameOverTheSizeLimitIsRejected() throws Exception {
        RawClient client = new RawClient();
        WebSocketSession session = connect(client);
        String frame = "SUBSCRIBE\nid:sub-0\ndestination:/topic/orders/1\npadding:"
                + "x".repeat(WebSocketConfig.MESSAGE_SIZE_LIMIT_BYTES) + "\n\n\0";

        // Tomcat buffers one WebSocket message in 8 KiB by default and would close the socket itself on a bigger one.
        // STOMP lets a frame span messages, so two halves get past Tomcat and reach the STOMP decoder's limit.
        int half = frame.length() / 2;
        session.sendMessage(new TextMessage(frame.substring(0, half)));
        session.sendMessage(new TextMessage(frame.substring(half)));

        assertThat(client.nextFrame())
                .startsWith("ERROR")
                .contains("buffer size limit of " + WebSocketConfig.MESSAGE_SIZE_LIMIT_BYTES);
        // STOMP ends the connection after an ERROR frame; Spring closes it from the server side.
        assertThat(client.closed.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isNotNull();
    }
}
