package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.contracts.CourierLocationUpdated;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes {@link CourierLocationUpdated} for a courier who is delivering an order (M4-2, tickets #12, #15, #42).
 *
 * <p>Only a courier whose {@code dispatch_orders} row is ASSIGNED or PICKED_UP has someone watching. At most one event
 * per courier goes out every 5 s: the update that wins {@code SET courier:{id}:loc-pub 1 NX EX 5} publishes, and the
 * rest are dropped until Redis expires the key. The key lives in Redis, so the limit holds across app instances.
 *
 * <p>The check and the publish run after the location commits, so a rolled-back update neither opens the window nor
 * reaches a customer. The event goes to plain {@code @EventListener}s, not the event registry: it is high-volume and
 * fine to lose. A failed Redis call or listener is logged and dropped; the location is already saved.
 */
@Component
class CourierLocationStream {

    static final Duration WINDOW = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(CourierLocationStream.class);

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;

    CourierLocationStream(JdbcClient jdbc, StringRedisTemplate redis, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.events = events;
    }

    static String throttleKey(long courierId) {
        return "courier:" + courierId + ":loc-pub";
    }

    /** Queues the event for after commit if the courier is delivering an order; does nothing otherwise. */
    @Transactional(propagation = Propagation.MANDATORY)
    void publishAfterCommit(long courierId, Position position, Instant at) {
        Optional<Long> orderId = deliveringOrder(courierId);
        if (orderId.isEmpty()) {
            return;
        }
        CourierLocationUpdated event = new CourierLocationUpdated(
                UUID.randomUUID(), courierId, orderId.get(), position.lat(), position.lng(), at, at);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(throttleKey(courierId), "1", WINDOW))) {
                        events.publishEvent(event);
                    }
                } catch (RuntimeException ex) {
                    log.warn("CourierLocationUpdated for courier {} was dropped", courierId, ex);
                }
            }
        });
    }

    /** The order the courier has accepted and not yet delivered; a courier holds at most one. */
    private Optional<Long> deliveringOrder(long courierId) {
        return jdbc.sql("SELECT order_id FROM dispatch_orders WHERE courier_id = ?"
                        + " AND status IN ('ASSIGNED', 'PICKED_UP')")
                .param(courierId)
                .query(Long.class)
                .optional();
    }
}
