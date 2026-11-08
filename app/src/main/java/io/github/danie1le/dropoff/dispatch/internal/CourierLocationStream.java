package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.contracts.CourierLocationUpdated;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes {@link CourierLocationUpdated} for a courier who is delivering an order (M4-2, tickets #12, #15, #42).
 *
 * <p>Only a courier whose {@code dispatch_orders} rows include an ASSIGNED or PICKED_UP one has someone watching. At
 * most one update per courier goes out every 5 s: the update that wins {@code SET courier:{id}:loc-pub 1 NX EX 5}
 * publishes, and the rest are dropped until Redis expires the key. The key lives in Redis, so the limit holds across
 * app instances. A courier can hold several active orders at once (a late order given back, ticket #85), so the winner
 * publishes one event per order, all in the same window.
 *
 * <p>The throttle, the order lookup and the publish all run after the location commits. A rolled-back update neither
 * opens the window nor reaches a customer, and the lookup runs only for the update that wins the window, outside the
 * transaction that holds the courier row lock. An idle courier also opens the window, which only means their first
 * update after accepting an order may wait up to 5 s. The event goes straight to the {@code courier-locations} topic
 * with {@code KafkaTemplate}, keyed by orderId, not through the event registry (M5-1, ticket #15): it is high-volume
 * and fine to lose. A failed Redis call, lookup or send is logged and dropped; the location is already saved.
 */
@Component
class CourierLocationStream {

    static final Duration WINDOW = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(CourierLocationStream.class);

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final KafkaOperations<?, ?> kafka;

    CourierLocationStream(JdbcClient jdbc, StringRedisTemplate redis, KafkaOperations<?, ?> kafka) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.kafka = kafka;
    }

    static String throttleKey(long courierId) {
        return "courier:" + courierId + ":loc-pub";
    }

    /** Queues the throttled publish for after commit; it does nothing if the courier is delivering no order. */
    @Transactional(propagation = Propagation.MANDATORY)
    void publishAfterCommit(long courierId, Position position, Instant at) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(throttleKey(courierId), "1", WINDOW))) {
                        for (long orderId : deliveringOrders(courierId)) {
                            send(new CourierLocationUpdated(
                                    UUID.randomUUID(), courierId, orderId, position.lat(), position.lng(), at, at));
                        }
                    }
                } catch (RuntimeException ex) {
                    log.warn("CourierLocationUpdated for courier {} was dropped", courierId, ex);
                }
            }
        });
    }

    /** Fire and forget: the callback logs a failed send, and nothing waits for it. */
    @SuppressWarnings("FutureReturnValueIgnored")
    private void send(CourierLocationUpdated event) {
        kafka.send(MessageBuilder.withPayload(event)
                        .setHeader(KafkaHeaders.TOPIC, KafkaTopics.COURIER_LOCATIONS)
                        .setHeader(KafkaHeaders.KEY, Long.toString(event.orderId()))
                        .build())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("CourierLocationUpdated for courier {} was dropped", event.courierId(), ex);
                    }
                });
    }

    /**
     * The orders the courier has accepted and not yet delivered. Usually one, but a courier given a late order back can
     * hold two (ticket #85).
     */
    private List<Long> deliveringOrders(long courierId) {
        return jdbc.sql("SELECT order_id FROM dispatch_orders WHERE courier_id = ?"
                        + " AND status IN ('ASSIGNED', 'PICKED_UP') ORDER BY order_id")
                .param(courierId)
                .query(Long.class)
                .list();
    }
}
