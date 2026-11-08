package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.common.ProcessedEvents;
import io.github.danie1le.dropoff.contracts.CourierAssigned;
import io.github.danie1le.dropoff.contracts.CourierUnassigned;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The order module reads dispatch's courier events from Kafka as the {@code order-service} group (M5-1, tickets #32,
 * #42). One listener per topic and group: two listeners in one group would split the topic's partitions between them.
 * Each event runs once per group ({@link ProcessedEvents}); a failure is retried, then dead-lettered.
 */
@Component
@KafkaListener(topics = KafkaTopics.COURIER_EVENTS, groupId = CourierEventsConsumer.GROUP)
class CourierEventsConsumer {

    static final String GROUP = "order-service";

    private final ProcessedEvents processed;
    private final CourierAssignedListener assigned;
    private final CourierUnassignedListener unassigned;

    CourierEventsConsumer(
            ProcessedEvents processed, CourierAssignedListener assigned, CourierUnassignedListener unassigned) {
        this.processed = processed;
        this.assigned = assigned;
        this.unassigned = unassigned;
    }

    @KafkaHandler
    void on(CourierAssigned event) {
        processed.runOnce(GROUP, event.eventId(), () -> assigned.on(event));
    }

    @KafkaHandler
    void on(CourierUnassigned event) {
        processed.runOnce(GROUP, event.eventId(), () -> unassigned.on(event));
    }
}
