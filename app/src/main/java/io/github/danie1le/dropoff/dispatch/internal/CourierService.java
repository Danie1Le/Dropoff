package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.common.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Couriers, their status and their location (tickets #12, #42). Keeps the Redis geo set in step with Postgres. */
@Service
class CourierService {

    private static final Logger log = LoggerFactory.getLogger(CourierService.class);

    private final CourierRepository couriers;
    private final CourierGeoIndex geoIndex;
    private final CourierAssignments assignments;
    private final DispatchService dispatch;
    private final TransactionTemplate transaction;
    private final Clock clock;

    CourierService(
            CourierRepository couriers,
            CourierGeoIndex geoIndex,
            CourierAssignments assignments,
            DispatchService dispatch,
            TransactionTemplate transaction,
            Clock clock) {
        this.couriers = couriers;
        this.geoIndex = geoIndex;
        this.assignments = assignments;
        this.dispatch = dispatch;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Transactional
    Courier create(String name) {
        return couriers.save(new Courier(name.trim(), DbClock.now(clock)));
    }

    @Transactional(readOnly = true)
    Courier get(long id) {
        return couriers.findById(id).orElseThrow(() -> notFound(id));
    }

    /**
     * AVAILABLE adds the courier to the geo set at their last location; OFFLINE removes them and releases what they
     * hold (M3-4, ticket #85): an open offer is declined, an ASSIGNED order is unassigned, and a picked-up order refuses
     * the change with 409 {@code COURIER_HAS_ACTIVE_DELIVERY}. Released orders are dispatched again after the change
     * commits, each in its own transaction, so a failed dispatch cannot undo it; it is logged and the dispatch-again
     * job retries the order.
     */
    Courier changeStatus(long id, CourierStatus requested) {
        Changed changed = Objects.requireNonNull(transaction.execute(status -> changeAndRelease(id, requested)));
        for (long orderId : changed.released()) {
            try {
                dispatch.dispatch(orderId);
            } catch (RuntimeException e) {
                log.warn(
                        "Dispatch of order {} after its courier went OFFLINE failed; the dispatch-again job will"
                                + " retry it",
                        orderId,
                        e);
            }
        }
        return changed.courier();
    }

    private record Changed(Courier courier, List<Long> released) {}

    private Changed changeAndRelease(long id, CourierStatus requested) {
        Courier courier = lockedOrThrow(id);
        Instant now = DbClock.now(clock);
        boolean offline = requested == CourierStatus.OFFLINE;
        courier.changeStatus(requested, offline && assignments.hasActiveDelivery(id), now);
        List<Long> released = offline ? assignments.releaseForOffline(id, now) : List.of();
        syncGeoIndex(courier);
        return new Changed(courier, released);
    }

    /**
     * Always saved; also moves the courier in the geo set while AVAILABLE (ticket #12 as amended by #42). Otherwise it
     * ZREMs the courier, which is idempotent and clears a member left behind by a failed or reordered write.
     */
    @Transactional
    Courier moveTo(long id, Position position) {
        Courier courier = lockedOrThrow(id);
        courier.moveTo(position, DbClock.now(clock));
        syncGeoIndex(courier);
        return courier;
    }

    /**
     * The courier, locked until the caller's transaction ends. Offer commands lock the courier before the offer, the
     * same order as dispatch's claim, so the two never deadlock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Courier lock(long id) {
        return lockedOrThrow(id);
    }

    /** Frees a courier locked with {@link #lock} after they decline an offer, and puts them back in the geo set. */
    @Transactional(propagation = Propagation.MANDATORY)
    void release(Courier courier) {
        courier.release(DbClock.now(clock));
        syncGeoIndex(courier);
    }

    /**
     * Takes a courier locked by the offer-timeout job off work: they let an offer expire, so they go OFFLINE (ticket
     * #11, point 8).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void timeOut(long id) {
        Courier courier = lockedOrThrow(id);
        courier.timeOut(DbClock.now(clock));
        syncGeoIndex(courier);
    }

    private void syncGeoIndex(Courier courier) {
        if (courier.status() == CourierStatus.AVAILABLE) {
            // CourierStatus.change never yields AVAILABLE without a location.
            geoIndex.addAfterCommit(courier.id(), courier.position().orElseThrow());
        } else {
            geoIndex.removeAfterCommit(courier.id());
        }
    }

    private Courier lockedOrThrow(long id) {
        return couriers.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
    }

    private static DomainException notFound(long id) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "COURIER_NOT_FOUND", "Courier " + id + " does not exist.");
    }
}
