package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.common.ProcessedEvents;
import io.github.danie1le.dropoff.contracts.OrderDelivered;
import io.github.danie1le.dropoff.contracts.OrderPickedUp;
import io.github.danie1le.dropoff.contracts.OrderReady;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dispatch reads the order module's events from Kafka as the {@code dispatch-service} group (M5-1, tickets #32, #42).
 * One listener per topic and group: two listeners in one group would split the topic's partitions between them. Each
 * event runs once per group ({@link ProcessedEvents}); a failure is retried, then dead-lettered.
 */
@Component
@KafkaListener(topics = KafkaTopics.ORDER_EVENTS, groupId = OrderEventsConsumer.GROUP)
class OrderEventsConsumer {

    static final String GROUP = "dispatch-service";

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConsumer.class);

    private final ProcessedEvents processed;
    private final DispatchService dispatch;
    private final DeliveryService delivery;

    OrderEventsConsumer(ProcessedEvents processed, DispatchService dispatch, DeliveryService delivery) {
        this.processed = processed;
        this.dispatch = dispatch;
        this.delivery = delivery;
    }

    /**
     * Starts dispatch when an order is ready (M3-2). The WAITING row commits first, with the event id, so a failed
     * dispatch cannot roll it back and leave the order stuck (ADR-0006, M3-2 exception). A failed dispatch is only
     * logged: the dispatch-again job retries it.
     */
    @KafkaHandler
    void on(OrderReady event) {
        if (!processed.runOnce(GROUP, event.eventId(), () -> dispatch.open(event))) {
            return;
        }
        try {
            dispatch.dispatch(event.orderId());
        } catch (RuntimeException e) {
            log.warn("Dispatch of order {} failed; the dispatch-again job will retry it", event.orderId(), e);
        }
    }

    /** Follows the order through pickup (M3-5). */
    @KafkaHandler
    void on(OrderPickedUp event) {
        processed.runOnce(GROUP, event.eventId(), () -> delivery.pickedUp(event.orderId(), event.courierId()));
    }

    /** Follows the order through delivery (M3-5). */
    @KafkaHandler
    void on(OrderDelivered event) {
        processed.runOnce(GROUP, event.eventId(), () -> delivery.delivered(event.orderId(), event.courierId()));
    }

    /** Dispatch has no use for the other order events (OrderPlaced, OrderCancelled). */
    @KafkaHandler(isDefault = true)
    void ignore(Object event) {}
}
