package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.contracts.CourierLocationUpdated;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Pushes the courier's location to the order's live topic (M4-2, tickets #12, #15). Reads {@code courier-locations} as
 * the {@code order-service} group (M5-1). No dedupe: the topic skips the outbox, a lost update is replaced within 5 s,
 * and pushing one twice does no harm. Dispatch already limits it to one per courier and order every 5 s, so every
 * event received is sent.
 *
 * <p>One order-service instance only (M5-6). Clients subscribe to the in-memory STOMP broker of the instance they are
 * connected to, and one consumer group splits the topic's partitions between instances, so with two instances a
 * client would miss the locations the other one reads. A second instance needs its own group for this listener (for
 * example {@code order-service-${random.uuid}} with {@code auto.offset.reset=latest}), or a shared broker relay.
 */
@Component
class CourierLocationListener {

    private final OrderUpdates updates;

    CourierLocationListener(OrderUpdates updates) {
        this.updates = updates;
    }

    @KafkaListener(topics = KafkaTopics.COURIER_LOCATIONS, groupId = CourierEventsConsumer.GROUP)
    void on(CourierLocationUpdated event) {
        updates.location(
                new OrderLocationMessage(event.orderId(), event.courierId(), event.lat(), event.lng(), event.at()));
    }
}
