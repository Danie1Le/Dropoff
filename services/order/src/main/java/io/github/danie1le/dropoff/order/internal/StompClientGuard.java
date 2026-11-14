package io.github.danie1le.dropoff.order.internal;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Checks frames from STOMP clients (M4-1). Clients only listen: a {@code SEND} is rejected, because the simple broker
 * would otherwise relay it to every subscriber, letting anyone fake a status on any order's topic. A {@code SUBSCRIBE}
 * must name one order's topic, so a wildcard such as {@code /topic/orders/**} cannot watch every order at once.
 *
 * <p>Each session may hold at most {@link #MAX_SUBSCRIPTIONS_PER_SESSION} subscriptions, so one socket cannot grow the
 * broker's in-memory registry without bound. {@code UNSUBSCRIBE} frees a slot and {@code DISCONNECT} drops the
 * session. Spring also sends a {@code DISCONNECT} through this channel when a socket closes without one, so a dropped
 * connection is cleared too.
 */
@Component
class StompClientGuard implements ChannelInterceptor {

    static final int MAX_SUBSCRIPTIONS_PER_SESSION = 50;

    private static final Pattern ORDER_TOPIC = Pattern.compile("/topic/orders/\\d+");

    /** Subscription ids per STOMP session id. */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null) {
            return message;
        }
        StompCommand command = stomp.getCommand();
        if (command == null) {
            return message;
        }
        switch (command) {
            case SEND ->
                throw new MessageDeliveryException(message, "Clients may not SEND; subscribe to /topic/orders/{id}.");
            case SUBSCRIBE -> {
                if (!isOrderTopic(stomp.getDestination())) {
                    throw new MessageDeliveryException(message, "Only /topic/orders/{id} can be subscribed to.");
                }
                if (!track(stomp.getSessionId(), stomp.getSubscriptionId())) {
                    throw new MessageDeliveryException(
                            message, "At most " + MAX_SUBSCRIPTIONS_PER_SESSION + " subscriptions per session.");
                }
            }
            case UNSUBSCRIBE -> untrack(stomp.getSessionId(), stomp.getSubscriptionId());
            case DISCONNECT -> forget(stomp.getSessionId());
            default -> {}
        }
        return message;
    }

    /**
     * Number of sessions holding at least one subscription. Package-private for tests, which use it to check that
     * closed sessions are removed from the map.
     */
    int trackedSessions() {
        return subscriptions.size();
    }

    private static boolean isOrderTopic(@Nullable String destination) {
        return destination != null && ORDER_TOPIC.matcher(destination).matches();
    }

    /** Records the subscription, or returns {@code false} if the session is already at the cap. */
    private boolean track(@Nullable String sessionId, @Nullable String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            // The broker ignores a SUBSCRIBE without these, so there is nothing to count.
            return true;
        }
        // The check and the add happen together inside compute, so the cap holds even if two frames race.
        Set<String> held = subscriptions.compute(sessionId, (id, ids) -> {
            Set<String> set = ids != null ? ids : ConcurrentHashMap.newKeySet();
            if (set.size() < MAX_SUBSCRIPTIONS_PER_SESSION) {
                set.add(subscriptionId);
            }
            return set;
        });
        // Already present (a repeated id) or just added; absent only when the session was full.
        return held.contains(subscriptionId);
    }

    private void untrack(@Nullable String sessionId, @Nullable String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            return;
        }
        subscriptions.computeIfPresent(sessionId, (id, held) -> {
            held.remove(subscriptionId);
            return held.isEmpty() ? null : held;
        });
    }

    private void forget(@Nullable String sessionId) {
        if (sessionId != null) {
            subscriptions.remove(sessionId);
        }
    }
}
