package io.github.danie1le.dropoff.common;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer-side dedupe for Kafka events (ticket #15, ADR-0006). Kafka delivers at least once, so a consumer handles
 * each event through {@link #runOnce}, which records the event id in {@code processed_events} in the transaction that
 * runs the work.
 *
 * <p>The work and the event id commit together only when the work joins that transaction. Work that opens its own
 * ({@code REQUIRES_NEW}, as dispatch's pickup and delivery steps do) commits first, and the event id commits after it.
 * A crash between the two commits leaves the work applied with no event id, so the redelivery runs the work again.
 * For such work the event id is only a fast path: the work must be idempotent on its own, through guarded updates.
 */
@Component
public class ProcessedEvents {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEvents.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    ProcessedEvents(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Runs {@code work} unless the consumer group already processed this event. A failed handler leaves no record, so
     * its redelivery runs again. Work that joins this transaction commits or rolls back with the event id; work in its
     * own transaction may commit without it (see the class comment).
     *
     * @return whether the work ran
     */
    @Transactional
    public boolean runOnce(String consumerGroup, UUID eventId, Runnable work) {
        int inserted = jdbc.sql("INSERT INTO processed_events (consumer_group, event_id, processed_at)"
                        + " VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                .params(consumerGroup, eventId, Timestamp.from(DbClock.now(clock)))
                .update();
        if (inserted == 0) {
            log.info("Skipped event {}: consumer group {} already processed it", eventId, consumerGroup);
            return false;
        }
        work.run();
        return true;
    }
}
