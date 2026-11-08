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
 * each event through {@link #runOnce}, which records the event id in {@code processed_events} in the same transaction
 * as the work.
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
     * Runs {@code work} unless the consumer group already processed this event. The event id and the work commit or
     * roll back together, so a failed handler leaves no record and its redelivery runs again.
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
