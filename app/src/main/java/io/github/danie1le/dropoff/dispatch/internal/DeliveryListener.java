package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.contracts.OrderDelivered;
import io.github.danie1le.dropoff.contracts.OrderPickedUp;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Follows the order through pickup and delivery (M3-5). Runs after the order module's commit, through the Modulith
 * event registry, which retries an unfinished delivery (ADR-0006).
 */
@Component
class DeliveryListener {

    private final DeliveryService delivery;

    DeliveryListener(DeliveryService delivery) {
        this.delivery = delivery;
    }

    @ApplicationModuleListener
    void on(OrderPickedUp event) {
        delivery.pickedUp(event.orderId(), event.courierId());
    }

    @ApplicationModuleListener
    void on(OrderDelivered event) {
        delivery.delivered(event.orderId(), event.courierId());
    }
}
