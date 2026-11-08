package io.github.danie1le.dropoff.order.internal;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * Checks frames from STOMP clients (M4-1). Clients only listen: a {@code SEND} is rejected, because the simple broker
 * would otherwise relay it to every subscriber, letting anyone fake a status on any order's topic. A {@code SUBSCRIBE}
 * must name one order's topic, so a wildcard such as {@code /topic/orders/**} cannot watch every order at once.
 */
class StompClientGuard implements ChannelInterceptor {

    private static final Pattern ORDER_TOPIC = Pattern.compile("/topic/orders/\\d+");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null) {
            return message;
        }
        StompCommand command = stomp.getCommand();
        if (command == StompCommand.SEND) {
            throw new MessageDeliveryException(message, "Clients may not SEND; subscribe to /topic/orders/{id}.");
        }
        if (command == StompCommand.SUBSCRIBE && !isOrderTopic(stomp.getDestination())) {
            throw new MessageDeliveryException(message, "Only /topic/orders/{id} can be subscribed to.");
        }
        return message;
    }

    private static boolean isOrderTopic(@Nullable String destination) {
        return destination != null && ORDER_TOPIC.matcher(destination).matches();
    }
}
