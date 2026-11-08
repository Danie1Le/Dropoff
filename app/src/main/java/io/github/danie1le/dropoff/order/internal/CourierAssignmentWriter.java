package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.CourierAssigned;
import io.github.danie1le.dropoff.contracts.CourierUnassigned;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes dispatch's courier assignments onto the order: {@link #record} for {@code CourierAssigned} (M3-3, ticket #11,
 * point 4) and {@link #clear} for {@code CourierUnassigned} (M3-4, ticket #11 point 10, ticket #85). It listens to
 * nothing itself: {@link CourierEventsConsumer} calls it for each event read from Kafka, inside the transaction that
 * records the event as processed (M5-1, ADR-0006). Do not make it an event listener as well, or each event would be
 * applied twice.
 *
 * <p>Since M3-4 an order can go assign A, unassign A, assign B while READY, so both writes are guarded by an offer-id
 * high-water mark ({@code assigned_offer_id}, ticket #85). A redelivered, reordered or late event matches nothing, and
 * every skip is logged.
 */
@Component
class CourierAssignmentWriter {

    private static final Logger log = LoggerFactory.getLogger(CourierAssignmentWriter.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    CourierAssignmentWriter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records the courier. Only a READY order takes a courier: a cancelled order, or one already past READY, is left
     * alone. The courier is written only for an offer newer than the one recorded, so an event for an older or the same
     * offer, even after {@link #clear} removed the courier, matches nothing.
     */
    void record(CourierAssigned event) {
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

    /**
     * Clears the courier. Only the assignment the event undoes is cleared: the order must still be READY, with this
     * courier, recorded from this offer. {@code assigned_offer_id} is kept, so a redelivered {@code CourierAssigned} for
     * the same offer cannot put the courier back.
     */
    void clear(CourierUnassigned event) {
        int updated = jdbc.sql("UPDATE orders SET courier_id = NULL, updated_at = :now"
                        + " WHERE id = :id AND courier_id = :courierId AND assigned_offer_id = :offerId"
                        + " AND status = 'READY'")
                .param("now", Timestamp.from(DbClock.now(clock)))
                .param("id", event.orderId())
                .param("courierId", event.courierId())
                .param("offerId", event.offerId())
                .update();
        if (updated == 0) {
            log.warn(
                    "Skipped CourierUnassigned {}: order {} is not READY with courier {} from offer {}",
                    event.eventId(),
                    event.orderId(),
                    event.courierId(),
                    event.offerId());
        }
    }
}
