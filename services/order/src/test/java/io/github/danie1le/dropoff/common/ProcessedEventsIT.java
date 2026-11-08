package io.github.danie1le.dropoff.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A consumer group handles each event once (ticket #15). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProcessedEventsIT {

    private final ProcessedEvents processed;
    private final JdbcClient jdbc;
    private final AtomicInteger runs = new AtomicInteger();

    ProcessedEventsIT(@Autowired ProcessedEvents processed, @Autowired JdbcClient jdbc) {
        this.processed = processed;
        this.jdbc = jdbc;
    }

    @Test
    void aRepeatedEventIsSkipped() {
        UUID eventId = UUID.randomUUID();

        assertThat(processed.runOnce("order-service", eventId, runs::incrementAndGet))
                .isTrue();
        assertThat(processed.runOnce("order-service", eventId, runs::incrementAndGet))
                .isFalse();

        assertThat(runs).hasValue(1);
    }

    @Test
    void eachGroupProcessesTheEvent() {
        UUID eventId = UUID.randomUUID();

        processed.runOnce("order-service", eventId, runs::incrementAndGet);
        processed.runOnce("dispatch-service", eventId, runs::incrementAndGet);

        assertThat(runs).hasValue(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedWorkLeavesNoRecordSoTheRedeliveryRuns() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> processed.runOnce("order-service", eventId, () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(processed.runOnce("order-service", eventId, runs::incrementAndGet))
                .isTrue();
        assertThat(runs).hasValue(1);
        // Committed outside the test transaction, so delete it here.
        jdbc.sql("DELETE FROM processed_events WHERE event_id = ?")
                .param(eventId)
                .update();
    }
}
