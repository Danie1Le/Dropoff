package io.github.danie1le.dropoff.dispatch.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatch's side of pickup and delivery (M3-5, ticket #42). The order module owns the order status; dispatch only
 * follows it, moving its own {@code dispatch_orders} row from ASSIGNED to PICKED_UP to DONE.
 *
 * <p>Each update is guarded by {@code courier_id = :courierId} and the statuses it may start from, so a stale,
 * repeated or reordered event matches no row; that is logged and skipped. A delivery that arrives before its pickup
 * closes the order anyway, and the late pickup is then skipped.
 */
@Service
class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final JdbcClient jdbc;
    private final CourierService couriers;

    DeliveryService(JdbcClient jdbc, CourierService couriers) {
        this.jdbc = jdbc;
        this.couriers = couriers;
    }

    /** ASSIGNED becomes PICKED_UP. */
    @Transactional
    void pickedUp(long orderId, long courierId) {
        int updated = jdbc.sql("UPDATE dispatch_orders SET status = 'PICKED_UP'"
                        + " WHERE order_id = :orderId AND courier_id = :courierId AND status = 'ASSIGNED'")
                .param("orderId", orderId)
                .param("courierId", courierId)
                .update();
        if (updated == 0) {
            log.warn("Skipped OrderPickedUp: order {} is not ASSIGNED to courier {}", orderId, courierId);
        }
    }

    /**
     * ASSIGNED or PICKED_UP becomes DONE, and the courier becomes AVAILABLE again and goes back in the geo set. Locks the
     * courier before {@code dispatch_orders}, the lock order every dispatch command uses.
     */
    @Transactional
    void delivered(long orderId, long courierId) {
        Courier courier = couriers.lock(courierId);
        int updated = jdbc.sql("UPDATE dispatch_orders SET status = 'DONE' WHERE order_id = :orderId"
                        + " AND courier_id = :courierId AND status IN ('ASSIGNED', 'PICKED_UP')")
                .param("orderId", orderId)
                .param("courierId", courierId)
                .update();
        if (updated == 0) {
            log.warn("Skipped OrderDelivered: order {} is not open with courier {}", orderId, courierId);
            return;
        }
        couriers.release(courier);
    }
}
