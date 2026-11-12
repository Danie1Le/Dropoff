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

    @Test
    void rejectsASubscriptionPastTheCap() {
        fillToCap("s1");

        assertThatThrownBy(() -> guard.preSend(subscribe("s1", "over"), channel))
                .isInstanceOf(MessageDeliveryException.class);
        // The cap is per session: another socket still gets in.
        Message<byte[]> other = subscribe("s2", "sub-0");
        assertThat(guard.preSend(other, channel)).isSameAs(other);
    }

    @Test
    void unsubscribeFreesASlot() {
        fillToCap("s1");

        guard.preSend(session(StompCommand.UNSUBSCRIBE, "s1", "sub-0"), channel);

        Message<byte[]> next = subscribe("s1", "next");
        assertThat(guard.preSend(next, channel)).isSameAs(next);
    }

    @Test
    void disconnectClearsTheSession() {
        fillToCap("s1");

        guard.preSend(session(StompCommand.DISCONNECT, "s1", null), channel);

        assertThat(guard.trackedSessions()).isZero();
        Message<byte[]> again = subscribe("s1", "sub-0");
        assertThat(guard.preSend(again, channel)).isSameAs(again);
    }

    @Test
    void aRepeatedSubscriptionIdCountsOnce() {
        fillToCap("s1");

        Message<byte[]> repeat = subscribe("s1", "sub-0");
        assertThat(guard.preSend(repeat, channel)).isSameAs(repeat);
    }

    private void fillToCap(String sessionId) {
        for (int i = 0; i < StompClientGuard.MAX_SUBSCRIPTIONS_PER_SESSION; i++) {
            guard.preSend(subscribe(sessionId, "sub-" + i), channel);
        }
    }

    private static Message<byte[]> subscribe(String sessionId, String subscriptionId) {
        StompHeaderAccessor stomp = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        stomp.setDestination("/topic/orders/42");
        stomp.setSessionId(sessionId);
        stomp.setSubscriptionId(subscriptionId);
        return MessageBuilder.createMessage(new byte[0], stomp.getMessageHeaders());
    }

    private static Message<byte[]> session(StompCommand command, String sessionId, @Nullable String subscriptionId) {
        StompHeaderAccessor stomp = StompHeaderAccessor.create(command);
        stomp.setSessionId(sessionId);
        if (subscriptionId != null) {
            stomp.setSubscriptionId(subscriptionId);
        }
        return MessageBuilder.createMessage(new byte[0], stomp.getMessageHeaders());
    }
}
