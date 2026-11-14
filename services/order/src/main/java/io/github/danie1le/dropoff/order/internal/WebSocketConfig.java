package io.github.danie1le.dropoff.order.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over raw WebSocket at {@code /ws}, with no SockJS fallback, so the simulator's hand-written client over
 * {@code java.net.http.WebSocket} can connect (M4-1, #42). An in-memory simple broker serves {@code /topic}; clients
 * subscribe to {@code /topic/orders/{id}} and never send (see {@link StompClientGuard}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Clients only send small control frames such as {@code SUBSCRIBE}, so 8 KiB is plenty. */
    static final int MESSAGE_SIZE_LIMIT_BYTES = 8 * 1024;

    private final StompClientGuard guard;

    WebSocketConfig(StompClientGuard guard) {
        this.guard = guard;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        // The outbound channel is a thread pool; without this, two STATUS messages for one session can swap places.
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT_BYTES);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(guard);
    }
}
