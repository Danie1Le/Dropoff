package io.github.danie1le.dropoff.order.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class StompClientGuardTest {

    private final StompClientGuard guard = new StompClientGuard();
    private final MessageChannel channel = mock(MessageChannel.class);

    private static Message<byte[]> frame(StompCommand command, @Nullable String destination) {
        StompHeaderAccessor stomp = StompHeaderAccessor.create(command);
        if (destination != null) {
            stomp.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], stomp.getMessageHeaders());
    }

    @Test
    void letsAClientSubscribeToOneOrdersTopic() {
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, "/topic/orders/42");

        assertThat(guard.preSend(subscribe, channel)).isSameAs(subscribe);
    }

    @Test
    void letsConnectAndDisconnectThrough() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, null);
        Message<byte[]> disconnect = frame(StompCommand.DISCONNECT, null);

        assertThat(guard.preSend(connect, channel)).isSameAs(connect);
        assertThat(guard.preSend(disconnect, channel)).isSameAs(disconnect);
    }

    @Test
    void rejectsAClientSend() {
        assertThatThrownBy(() -> guard.preSend(frame(StompCommand.SEND, "/topic/orders/42"), channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void rejectsAWildcardSubscription() {
        for (String destination : new String[] {"/topic/orders/*", "/topic/orders/**", "/topic/**"}) {
            assertThatThrownBy(() -> guard.preSend(frame(StompCommand.SUBSCRIBE, destination), channel))
                    .as(destination)
                    .isInstanceOf(MessageDeliveryException.class);
        }
    }

    @Test
    void rejectsASubscriptionOutsideTheOrderTopics() {
        assertThatThrownBy(() -> guard.preSend(frame(StompCommand.SUBSCRIBE, "/topic/couriers/1"), channel))
                .isInstanceOf(MessageDeliveryException.class);
    }
}
