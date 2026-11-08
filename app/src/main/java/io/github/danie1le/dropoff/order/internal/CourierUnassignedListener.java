package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.CourierUnassigned;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Clears the courier from the order once dispatch unassigns them (M3-4, tickets #11 point 10, #85). {@link
 * CourierEventsConsumer} calls it for each {@code CourierUnassigned} read from Kafka, inside the transaction that
 * records the event as processed (M5-1, ADR-0006).
 *
 * <p>Only the assignment the event undoes is cleared: the order must still be READY, with this courier, recorded from
 * this offer ({@code assigned_offer_id}). A late event that arrives after a newer courier was assigned, or a
 * redelivery, matches nothing; the skip is logged. {@code assigned_offer_id} is kept, so a redelivered {@code
 * CourierAssigned} for the same offer cannot put the courier back.
 */
@Component
class CourierUnassignedListener {

    private static final Logger log = LoggerFactory.getLogger(CourierUnassignedListener.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    CourierUnassignedListener(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void on(CourierUnassigned event) {
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
