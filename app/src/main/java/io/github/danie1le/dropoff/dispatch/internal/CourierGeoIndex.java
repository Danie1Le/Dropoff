package io.github.danie1le.dropoff.dispatch.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Redis geo set of AVAILABLE couriers that dispatch searches (ticket #42, point 10). Members are courier ids.
 *
 * <p>Every write is queued until the surrounding Postgres transaction commits (ticket #42, second gate pass), so a
 * rollback never leaves Redis and Postgres disagreeing. A failed Redis write is logged, not thrown: Postgres has
 * already committed and stays the source of truth, and the courier's next location update writes the set again.
 */
@Component
class CourierGeoIndex {

    static final String KEY = "couriers:available";

    private static final Logger log = LoggerFactory.getLogger(CourierGeoIndex.class);

    private final StringRedisTemplate redis;

    CourierGeoIndex(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** {@code GEOADD} after commit: the courier is AVAILABLE at {@code position}. */
    void addAfterCommit(long courierId, Position position) {
        afterCommit(
                "GEOADD",
                courierId,
                () -> redis.opsForGeo().add(KEY, new Point(position.lng(), position.lat()), Long.toString(courierId)));
    }

    /** {@code ZREM} after commit: the courier is no longer AVAILABLE. */
    void removeAfterCommit(long courierId) {
        afterCommit("ZREM", courierId, () -> redis.opsForGeo().remove(KEY, Long.toString(courierId)));
    }

    private static void afterCommit(String command, long courierId, Runnable write) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Redis geo writes must run inside a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    write.run();
                } catch (RuntimeException ex) {
                    log.warn("Redis {} for courier {} failed after commit", command, courierId, ex);
                }
            }
        });
    }
}
