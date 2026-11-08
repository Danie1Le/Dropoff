package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.common.DomainException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Couriers, their status and their location (tickets #12, #42). Keeps the Redis geo set in step with Postgres. */
@Service
class CourierService {

    private final CourierRepository couriers;
    private final CourierGeoIndex geoIndex;
    private final Clock clock;

    CourierService(CourierRepository couriers, CourierGeoIndex geoIndex, Clock clock) {
        this.couriers = couriers;
        this.geoIndex = geoIndex;
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

    /** AVAILABLE adds the courier to the geo set at their last location; OFFLINE removes them. */
    @Transactional
    Courier changeStatus(long id, CourierStatus requested) {
        Courier courier = lockedOrThrow(id);
        courier.changeStatus(requested, DbClock.now(clock));
        syncGeoIndex(courier);
        return courier;
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
