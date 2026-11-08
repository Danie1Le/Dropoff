package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.dispatch.internal.CandidateSelection.NearbyCourier;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Redis geo set of AVAILABLE couriers that dispatch searches (ticket #42, point 10). Members are courier ids.
 *
 * <p>Every write is queued until the surrounding Postgres transaction commits (ticket #42, second gate pass), so a
 * rollback never leaves Redis and Postgres disagreeing. A failed Redis write is logged, not thrown: Postgres has
 * already committed and stays the source of truth.
 *
 * <p>The set can still go stale. A failed write leaves it behind Postgres, and two requests can run their after-commit
 * writes in the opposite order to their commits, since the row lock is released at commit. Each location update writes
 * the set again (GEOADD while AVAILABLE, ZREM otherwise), which heals most of this on the courier's next ping. The set is
 * only a search hint: dispatch claims a courier with {@code UPDATE ... WHERE status = 'AVAILABLE'} in Postgres, and must
 * ZREM a member whose claim fails (ticket #83).
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

    /**
     * {@code GEOSEARCH} for members within {@code radius} of {@code center}, nearest first. A hint only: a courier found
     * here may no longer be AVAILABLE in Postgres.
     */
    List<NearbyCourier> nearest(Position center, Distance radius) {
        GeoResults<GeoLocation<String>> results = redis.opsForGeo()
                .search(
                        KEY,
                        GeoReference.fromCoordinate(center.lng(), center.lat()),
                        radius,
                        GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance()
                                .sortAscending());
        if (results == null) {
            return List.of();
        }
        return results.getContent().stream()
                .map(result -> new NearbyCourier(
                        Long.parseLong(result.getContent().getName()),
                        result.getDistance().in(Metrics.KILOMETERS).getValue()))
                .toList();
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
