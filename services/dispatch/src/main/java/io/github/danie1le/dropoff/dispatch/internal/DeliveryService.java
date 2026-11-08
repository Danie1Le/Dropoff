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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dispatch's side of pickup and delivery (M3-5, ticket #42). The order module owns the order status; dispatch only
 * follows it, moving its own {@code dispatch_orders} row from ASSIGNED to PICKED_UP to DONE.
 *
 * <p>Each update is guarded by {@code courier_id = :courierId} and the statuses it may start from, so a stale,
 * repeated or reordered event matches no row; that is logged and skipped. A delivery that arrives before its pickup
 * closes the order anyway, and the late pickup is then skipped.
 *
 * <p>The order's status is the truth (ticket #85). A courier can go OFFLINE after the order module commits the pickup
 * but before dispatch hears of it; dispatch then unassigns the order and may offer it to someone else. When the late
 * pickup or delivery arrives, dispatch takes the order back for the courier who carries it: see {@link #takeBack}.
 *
 * <p>A courier is freed after a delivery only if they hold no other ASSIGNED or PICKED_UP order, so a courier who took
 * a second order after coming back keeps working on it.
 */
@Service
class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    /** How often a take-back is tried; it retries when its couriers change under it or it loses a lock race. */
    private static final int TAKE_BACK_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final CourierService couriers;
    private final DispatchService dispatch;
    private final TransactionTemplate ownTransaction;

    DeliveryService(
            JdbcClient jdbc,
            CourierService couriers,
            DispatchService dispatch,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.couriers = couriers;
        this.dispatch = dispatch;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * ASSIGNED becomes PICKED_UP. If that guard misses, the courier may have gone OFFLINE before this event arrived, and
     * {@link #takeBack} restores the order to PICKED_UP with them.
     *
     * <p>Each step runs in its own transaction: a guarded update that waited and missed still holds the {@code
     * dispatch_orders} row lock, and locking couriers after it would break the lock order.
     */
    void pickedUp(long orderId, long courierId) {
        Integer updated = ownTransaction.execute(status -> jdbc.sql("UPDATE dispatch_orders SET status = 'PICKED_UP'"
                        + " WHERE order_id = :orderId AND courier_id = :courierId AND status = 'ASSIGNED'")
                .param("orderId", orderId)
                .param("courierId", courierId)
                .update());
        if (Objects.requireNonNull(updated) == 0) {
            takeBack(orderId, courierId, "PICKED_UP");
        }
    }

    /**
     * ASSIGNED or PICKED_UP becomes DONE, and the courier becomes AVAILABLE again and goes back in the geo set unless
     * they hold another open order. Locks the courier before {@code dispatch_orders}, the lock order every dispatch
     * command uses. If the guard misses, the courier may have gone OFFLINE before dispatch heard of the pickup, and
     * {@link #takeBack} closes the order with them; it runs in its own transaction for the reason given on {@link
     * #pickedUp}.
     */
    void delivered(long orderId, long courierId) {
        Boolean done = ownTransaction.execute(status -> {
            Courier courier = couriers.lock(courierId);
            int updated = jdbc.sql("UPDATE dispatch_orders SET status = 'DONE' WHERE order_id = :orderId"
                            + " AND courier_id = :courierId AND status IN ('ASSIGNED', 'PICKED_UP')")
                    .param("orderId", orderId)
                    .param("courierId", courierId)
                    .update();
            if (updated == 1) {
                releaseUnlessHoldingAnOrder(courier);
            }
            return updated == 1;
        });
        if (!Objects.requireNonNull(done)) {
            takeBack(orderId, courierId, "DONE");
        }
    }

    /**
     * Takes the order back for the courier who picked it up, as {@code target} (PICKED_UP or DONE). Only when the order
     * is still WAITING or ASSIGNED to someone else, and the courier holds an ACCEPTED offer for it, which means going
     * OFFLINE unassigned them; otherwise the event is stale and skipped.
     *
     * <p>Every open offer for the order is WITHDRAWN and the other couriers it involved are freed. The courier's own open
     * offers on other orders are WITHDRAWN too, since they are carrying this one, and those orders are dispatched again
     * after the commit. For PICKED_UP the courier becomes BUSY; for DONE they are freed unless they hold another order.
     *
     * <p>Locks the couriers first, in ascending id order, then the offers, then the {@code dispatch_orders} row: the lock
     * order every dispatch command uses. A courier who appears between reading and locking, or a lost lock race,
     * rolls the attempt back and it is retried; after the last attempt the failure is logged at ERROR and thrown. The
     * Kafka error handler then redelivers the event 3 more times and, if those fail too, sends it to {@code
     * order-events.DLT}. From then on dispatch disagrees with the order until someone replays the DLT with {@code
     * ops/kafka/replay-dlt.sh}; nothing retries it on its own.
     */
    private void takeBack(long orderId, long courierId, String target) {
        for (int attempt = 1; attempt <= TAKE_BACK_ATTEMPTS; attempt++) {
            TakeBack outcome;
            try {
                outcome = Objects.requireNonNull(
                        ownTransaction.execute(status -> tryTakeBack(orderId, courierId, target, status)));
            } catch (PessimisticLockingFailureException e) {
                log.debug("Taking back order {} for courier {} lost a lock race; retrying", orderId, courierId, e);
                outcome = TakeBack.RETRY;
            }
            if (outcome instanceof TakeBack.Done done) {
                log.warn(
                        "Order {} reached {} with courier {} after dispatch had unassigned them; took it back",
                        orderId,
                        target,
                        courierId);
                done.redispatch().forEach(this::dispatchAgain);
                return;
            }
            if (outcome instanceof TakeBack.Skipped) {
                log.warn("Skipped the {} event: order {} is not open with courier {}", target, orderId, courierId);
                return;
            }
        }
        log.error(
                "Could not take back order {} as {} for courier {} after {} attempts; dispatch disagrees with the order"
                        + " until the event is retried",
                orderId,
                target,
                courierId,
                TAKE_BACK_ATTEMPTS);
        throw new IllegalStateException("could not take back order " + orderId + " for courier " + courierId);
    }

    private sealed interface TakeBack {
        TakeBack SKIPPED = new Skipped();
        TakeBack RETRY = new Retry();

        /** Done; {@code redispatch} holds the orders whose offers to the courier were withdrawn. */
        record Done(List<Long> redispatch) implements TakeBack {}

        record Skipped() implements TakeBack {}

        record Retry() implements TakeBack {}
    }

    private record Row(String status, @Nullable Long courierId) {}

    private record OpenOffer(long orderId, long courierId) {}

    private TakeBack tryTakeBack(long orderId, long courierId, String target, TransactionStatus transaction) {
        Optional<Row> seen = row(orderId, "");
        if (seen.isEmpty() || !reassignable(seen.get()) || !accepted(orderId, courierId)) {
            return TakeBack.SKIPPED;
        }
        Set<Long> involved = new TreeSet<>();
        involved.add(courierId);
        if (seen.get().courierId() != null) {
            involved.add(seen.get().courierId());
        }
        openOffers(orderId, courierId, "").forEach(offer -> involved.add(offer.courierId()));
        Map<Long, Courier> locked = new TreeMap<>();
        for (long id : involved) {
            locked.put(id, couriers.lock(id));
        }
        List<OpenOffer> open = openOffers(orderId, courierId, " FOR UPDATE");
        Row row = row(orderId, " FOR UPDATE").orElseThrow();
        if (!reassignable(row)) {
            return TakeBack.SKIPPED;
        }
        Set<Long> toFree = new HashSet<>();
        open.forEach(offer -> toFree.add(offer.courierId()));
        if (row.courierId() != null) {
            toFree.add(row.courierId());
        }
        toFree.remove(courierId);
        if (!locked.keySet().containsAll(toFree)) {
            transaction.setRollbackOnly();
            return TakeBack.RETRY;
        }
        jdbc.sql("UPDATE offers SET status = 'WITHDRAWN', responded_at = now()"
                        + " WHERE (order_id = :orderId OR courier_id = :courierId) AND status = 'OFFERED'")
                .param("orderId", orderId)
                .param("courierId", courierId)
                .update();
        jdbc.sql("UPDATE dispatch_orders SET status = :target, courier_id = :courierId WHERE order_id = :orderId")
                .param("target", target)
                .param("courierId", courierId)
                .param("orderId", orderId)
                .update();
        for (long id : toFree) {
            releaseUnlessHoldingAnOrder(Objects.requireNonNull(locked.get(id)));
        }
        Courier courier = Objects.requireNonNull(locked.get(courierId));
        if (target.equals("PICKED_UP")) {
            couriers.resumeDelivery(courier);
        } else {
            releaseUnlessHoldingAnOrder(courier);
        }
        return new TakeBack.Done(open.stream()
                .filter(offer -> offer.orderId() != orderId)
                .map(OpenOffer::orderId)
                .toList());
    }

    /** Frees a locked courier unless they still hold an ASSIGNED or PICKED_UP order. */
    private void releaseUnlessHoldingAnOrder(Courier courier) {
        boolean holding = jdbc.sql("SELECT EXISTS (SELECT 1 FROM dispatch_orders WHERE courier_id = ?"
                        + " AND status IN ('ASSIGNED', 'PICKED_UP'))")
                .param(courier.id())
                .query(Boolean.class)
                .single();
        if (!holding) {
            couriers.release(courier);
        }
    }

    /** Dispatches the order in its own transaction; a failure is logged and left to the dispatch-again job. */
    private void dispatchAgain(long orderId) {
        try {
            dispatch.dispatch(orderId);
        } catch (RuntimeException e) {
            log.warn("Dispatch of order {} after a take-back failed; the dispatch-again job will retry it", orderId, e);
        }
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

    /**
     * The open offers for the order (one at most, by {@code one_open_offer_per_order}) and the courier's own open offers
     * on other orders.
     */
    private List<OpenOffer> openOffers(long orderId, long courierId, String lock) {
        return jdbc.sql("SELECT order_id, courier_id FROM offers"
                        + " WHERE (order_id = :orderId OR courier_id = :courierId) AND status = 'OFFERED'" + lock)
                .param("orderId", orderId)
                .param("courierId", courierId)
                .query((rs, n) -> new OpenOffer(rs.getLong("order_id"), rs.getLong("courier_id")))
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
}
