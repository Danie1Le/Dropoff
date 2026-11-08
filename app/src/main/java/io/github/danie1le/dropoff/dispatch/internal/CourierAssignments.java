package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.contracts.CourierUnassigned;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work a courier holds in dispatch, and its release when they go OFFLINE (M3-4, ticket #11 points 9 and 10, ticket
 * #85). The caller holds the courier's row lock, so these run in the lock order every offer command uses: courier,
 * then offer, then {@code dispatch_orders}.
 */
@Component
class CourierAssignments {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    CourierAssignments(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /**
     * Whether the courier has picked up an order that is not delivered yet. Locks the courier's open {@code
     * dispatch_orders} rows first, so a pickup still in flight is waited for and then seen, not missed (ticket #85).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean hasActiveDelivery(long courierId) {
        return jdbc.sql(
                        "SELECT status FROM dispatch_orders WHERE courier_id = ? AND status IN ('ASSIGNED', 'PICKED_UP')"
                                + " FOR UPDATE")
                .param(courierId)
                .query(String.class)
                .list()
                .contains("PICKED_UP");
    }

    /**
     * Releases what a courier going OFFLINE holds. An open offer closes as DECLINED, since the courier has said they
     * will not take it, or as TIMED_OUT if it had already expired, the status the timeout job would give it. An ASSIGNED order goes back to WAITING with no courier, and {@link
     * CourierUnassigned} is published for the order module, carrying the accepted offer it undoes.
     *
     * @return the orders to dispatch again once the caller's transaction commits
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Long> releaseForOffline(long courierId, Instant now) {
        List<Long> released = new ArrayList<>(jdbc.sql("UPDATE offers"
                        + " SET status = CASE WHEN expires_at > now() THEN 'DECLINED' ELSE 'TIMED_OUT' END,"
                        + " responded_at = now() WHERE courier_id = ? AND status = 'OFFERED' RETURNING order_id")
                .param(courierId)
                .query(Long.class)
                .list());
        List<Long> unassigned = jdbc.sql("UPDATE dispatch_orders SET status = 'WAITING', courier_id = NULL"
                        + " WHERE courier_id = ? AND status = 'ASSIGNED' RETURNING order_id")
                .param(courierId)
                .query(Long.class)
                .list();
        for (long orderId : unassigned) {
            events.publishEvent(new CourierUnassigned(
                    UUID.randomUUID(), orderId, courierId, acceptedOffer(orderId, courierId), now));
        }
        released.addAll(unassigned);
        return released;
    }

    /** The offer the courier accepted for the order; an ASSIGNED order always has one. */
    private long acceptedOffer(long orderId, long courierId) {
        return jdbc.sql("SELECT id FROM offers WHERE order_id = ? AND courier_id = ? AND status = 'ACCEPTED'"
                        + " ORDER BY id DESC LIMIT 1")
                .params(orderId, courierId)
                .query(Long.class)
                .single();
    }
}
