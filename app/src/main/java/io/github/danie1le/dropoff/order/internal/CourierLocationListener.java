package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.contracts.CourierLocationUpdated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Pushes the courier's location to the order's live topic (M4-2, tickets #12, #15). A plain {@code @EventListener}, not
 * {@code @ApplicationModuleListener}: the event skips the registry, since losing one is fine. Dispatch already limits
 * it to one per courier and order every 5 s, so every event received is sent.
 */
@Component
class CourierLocationListener {

    private final OrderUpdates updates;

    CourierLocationListener(OrderUpdates updates) {
        this.updates = updates;
    }

    @EventListener
    void on(CourierLocationUpdated event) {
        updates.location(
                new OrderLocationMessage(event.orderId(), event.courierId(), event.lat(), event.lng(), event.at()));
    }
}
