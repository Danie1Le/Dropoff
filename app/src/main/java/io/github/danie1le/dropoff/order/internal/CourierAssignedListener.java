package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.CourierAssigned;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records the courier on the order once dispatch assigns one (M3-3, ticket #11, point 4). Runs after dispatch's
 * commit, through the Modulith event registry, which retries an unfinished delivery (ADR-0006).
 *
 * <p>Only a READY order takes a courier: a cancelled order, or one already past READY, is left alone, and the skip is
 * logged. While {@code CourierAssigned} is the only event that writes {@code orders.courier_id} (M3-3), an order gets
 * at most one, so a redelivery writes the same courier again. That stops holding once M3-4 adds {@code
 * CourierUnassigned}: a stale or reordered event could then restore an earlier courier, and #85 adds an offer-id
 * high-water mark to the guard.
 */
@Component
class CourierAssignedListener {

    private static final Logger log = LoggerFactory.getLogger(CourierAssignedListener.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    CourierAssignedListener(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(CourierAssigned event) {
        int updated = jdbc.sql(
                        "UPDATE orders SET courier_id = :courierId, updated_at = :now WHERE id = :id AND status = 'READY'")
                .param("courierId", event.courierId())
                .param("now", Timestamp.from(DbClock.now(clock)))
                .param("id", event.orderId())
                .update();
        if (updated == 0) {
            log.warn(
                    "Skipped CourierAssigned {}: order {} is not READY, so courier {} was not recorded",
                    event.eventId(),
                    event.orderId(),
                    event.courierId());
        }
    }
}
