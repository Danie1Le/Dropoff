package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.CourierAssigned;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Records the courier on the order once dispatch assigns one (M3-3, ticket #11, point 4). {@link
 * CourierEventsConsumer} calls it for each {@code CourierAssigned} read from Kafka, inside the transaction that records
 * the event as processed (M5-1, ADR-0006).
 *
 * <p>Only a READY order takes a courier: a cancelled order, or one already past READY, is left alone. Since M3-4 an
 * order can go assign A, unassign A, assign B while READY, so the guard also holds an offer-id high-water mark
 * ({@code assigned_offer_id}, ticket #85): the courier is written only for an offer newer than the one recorded. A
 * redelivered or reordered event for an older or the same offer, even after {@code CourierUnassigned} cleared the
 * courier, matches nothing. Every skip is logged.
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

    void on(CourierAssigned event) {
        int updated = jdbc.sql("UPDATE orders SET courier_id = :courierId, assigned_offer_id = :offerId,"
                        + " updated_at = :now WHERE id = :id AND status = 'READY'"
                        + " AND (assigned_offer_id IS NULL OR assigned_offer_id < :offerId)")
                .param("courierId", event.courierId())
                .param("offerId", event.offerId())
                .param("now", Timestamp.from(DbClock.now(clock)))
                .param("id", event.orderId())
                .update();
        if (updated == 0) {
            log.warn(
                    "Skipped CourierAssigned {}: order {} is not READY or already has offer {} or newer, so courier {}"
                            + " was not recorded",
                    event.eventId(),
                    event.orderId(),
                    event.offerId(),
                    event.courierId());
        }
    }
}
