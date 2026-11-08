package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class WebSocketTransportTest {

    @Test
    void pointsTheBaseUrlAtTheStompEndpoint() {
        assertThat(WebSocketTransport.wsUri(URI.create("http://localhost:8082")))
                .isEqualTo(URI.create("ws://localhost:8082/ws"));
        assertThat(WebSocketTransport.wsUri(URI.create("https://example.test/")))
                .isEqualTo(URI.create("wss://example.test/ws"));
    }
}
