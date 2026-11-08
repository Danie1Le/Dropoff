package io.github.danie1le.dropoff.dispatch.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dispatch's side of pickup and delivery (M3-5, ticket #42). The order module owns the order status; dispatch only
 * follows it, moving its own {@code dispatch_orders} row from ASSIGNED to PICKED_UP to DONE.
 *
 * <p>Each update is guarded by {@code courier_id = :courierId} and the statuses it may start from, so a stale,
 * repeated or reordered event matches no row; that is logged and skipped. A delivery that arrives before its pickup
 * closes the order anyway, and the late pickup is then skipped.
 *
 * <p>The order's PICKED_UP is the truth (ticket #85). A courier can go OFFLINE after the order module commits the
 * pickup but before dispatch hears of it; dispatch then unassigns the order and may offer it to someone else. When the
 * late pickup arrives, dispatch gives the order back to the courier who carries it: see {@link #pickedUp}.
 */
@Service
class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    /** How often {@link #pickedUp} retries a restore whose couriers changed between reading and locking them. */
    private static final int RESTORE_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final CourierService couriers;
    private final TransactionTemplate ownTransaction;

    DeliveryService(JdbcClient jdbc, CourierService couriers, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.couriers = couriers;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * ASSIGNED becomes PICKED_UP. If the order is not ASSIGNED to the courier but is still WAITING or ASSIGNED to
     * someone else, and the courier holds an ACCEPTED offer for it, the courier went OFFLINE before this event arrived:
     * dispatch restores the order to PICKED_UP with them, closes any open offer as WITHDRAWN, frees that offer's courier
     * and any other courier it was ASSIGNED to, and makes the courier BUSY again.
     *
     * <p>Each step runs in its own transaction, so a row lock left by the guarded update cannot block the restore. The
     * restore locks the couriers first, in ascending id order, then the order's open offers, then its {@code
     * dispatch_orders} row: the lock order every dispatch command uses. A courier who appears between reading and
     * locking rolls the attempt back, and it is retried.
     */
    void pickedUp(long orderId, long courierId) {
        Integer updated = ownTransaction.execute(status -> jdbc.sql("UPDATE dispatch_orders SET status = 'PICKED_UP'"
                        + " WHERE order_id = :orderId AND courier_id = :courierId AND status = 'ASSIGNED'")
                .param("orderId", orderId)
                .param("courierId", courierId)
                .update());
        if (Objects.requireNonNull(updated) == 1) {
            return;
        }
        for (int attempt = 1; attempt <= RESTORE_ATTEMPTS; attempt++) {
            Restore outcome =
                    Objects.requireNonNull(ownTransaction.execute(status -> restore(orderId, courierId, status)));
            switch (outcome) {
                case RESTORED -> {
                    log.warn(
                            "Order {} was picked up by courier {} after dispatch had unassigned them; restored it",
                            orderId,
                            courierId);
                    return;
                }
                case SKIPPED -> {
                    log.warn("Skipped OrderPickedUp: order {} is not ASSIGNED to courier {}", orderId, courierId);
                    return;
                }
                case RETRY -> log.debug("Couriers of order {} changed while restoring it; retrying", orderId);
            }
        }
        throw new IllegalStateException("could not restore order " + orderId + " to courier " + courierId);
    }

    private enum Restore {
        RESTORED,
        SKIPPED,
        RETRY
    }

    private record Row(String status, @Nullable Long courierId) {}

    private Restore restore(long orderId, long courierId, TransactionStatus transaction) {
        Optional<Row> seen = row(orderId, "");
        if (seen.isEmpty() || !reassignable(seen.get()) || !accepted(orderId, courierId)) {
            return Restore.SKIPPED;
        }
        Set<Long> involved = new TreeSet<>();
        involved.add(courierId);
        if (seen.get().courierId() != null) {
            involved.add(seen.get().courierId());
        }
        involved.addAll(openOfferCouriers(orderId, ""));
        Map<Long, Courier> locked = new TreeMap<>();
        for (long id : involved) {
            locked.put(id, couriers.lock(id));
        }
        List<Long> offeredTo = openOfferCouriers(orderId, " FOR UPDATE");
        Row row = row(orderId, " FOR UPDATE").orElseThrow();
        if (!reassignable(row)) {
            return Restore.SKIPPED;
        }
        Set<Long> toFree = new HashSet<>(offeredTo);
        if (row.courierId() != null) {
            toFree.add(row.courierId());
        }
        toFree.remove(courierId);
        if (!locked.keySet().containsAll(toFree)) {
            transaction.setRollbackOnly();
            return Restore.RETRY;
        }
        jdbc.sql(
                        "UPDATE offers SET status = 'WITHDRAWN', responded_at = now() WHERE order_id = ? AND status = 'OFFERED'")
                .param(orderId)
                .update();
        jdbc.sql("UPDATE dispatch_orders SET status = 'PICKED_UP', courier_id = :courierId WHERE order_id = :orderId")
                .param("courierId", courierId)
                .param("orderId", orderId)
                .update();
        for (long id : toFree) {
            couriers.release(Objects.requireNonNull(locked.get(id)));
        }
        couriers.resumeDelivery(Objects.requireNonNull(locked.get(courierId)));
        return Restore.RESTORED;
    }

    /** Whether the order is still open for a courier: not yet picked up or done. */
    private static boolean reassignable(Row row) {
        return row.status().equals("WAITING") || row.status().equals("ASSIGNED");
    }

    private Optional<Row> row(long orderId, String lock) {
        return jdbc.sql("SELECT status, courier_id FROM dispatch_orders WHERE order_id = ?" + lock)
                .param(orderId)
                .query((rs, n) -> new Row(rs.getString("status"), rs.getObject("courier_id", Long.class)))
                .optional();
    }

    /** The couriers holding the order's open offers; one at most, by {@code one_open_offer_per_order}. */
    private List<Long> openOfferCouriers(long orderId, String lock) {
        return jdbc.sql("SELECT courier_id FROM offers WHERE order_id = ? AND status = 'OFFERED'" + lock)
                .param(orderId)
                .query(Long.class)
                .list();
    }

    /** Whether the courier accepted an offer for the order, the only way the order module lets them pick it up. */
    private boolean accepted(long orderId, long courierId) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM offers WHERE order_id = ? AND courier_id = ? AND status = 'ACCEPTED')")
                .params(orderId, courierId)
                .query(Boolean.class)
                .single();
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
