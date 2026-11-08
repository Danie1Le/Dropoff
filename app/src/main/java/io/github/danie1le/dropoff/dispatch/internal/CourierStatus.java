package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.web.DomainException;

/** Whether a courier can get offers (GLOSSARY: Courier). The V8 CHECK repeats these values. */
enum CourierStatus {
    /** Not working. Not in the Redis geo set. */
    OFFLINE,
    /** Working and free. In the Redis geo set, so dispatch can find them. */
    AVAILABLE,
    /** Holding an offer or a delivery. Only the system sets it (M3-2); not in the Redis geo set. */
    BUSY;

    /**
     * The status after the courier asks for {@code requested} (ticket #42, point 10; ticket #85). OFFLINE is allowed
     * from any status unless the courier carries a picked-up order, which gets 409 COURIER_HAS_ACTIVE_DELIVERY: food
     * on the road must not lose its courier. Whatever else the courier holds is released by the caller: an open offer
     * is declined and an assigned order is unassigned. AVAILABLE needs a known location and is refused while BUSY.
     * Asking for the current status is a no-op.
     *
     * @param hasActiveDelivery whether the courier has an order in PICKED_UP
     * @throws IllegalArgumentException if {@code requested} is BUSY: the request validation lets only AVAILABLE and
     *     OFFLINE through
     */
    CourierStatus change(CourierStatus requested, boolean hasLocation, boolean hasActiveDelivery) {
        return switch (requested) {
            case OFFLINE -> {
                if (hasActiveDelivery) {
                    throw new DomainException(
                            DomainException.Category.CONFLICT,
                            "COURIER_HAS_ACTIVE_DELIVERY",
                            "The courier has picked up an order and cannot go OFFLINE until it is delivered.");
                }
                yield OFFLINE;
            }
            case AVAILABLE -> {
                if (this == BUSY) {
                    throw new DomainException(
                            DomainException.Category.CONFLICT,
                            "COURIER_BUSY",
                            "The courier has an offer or a delivery and cannot become AVAILABLE.");
                }
                if (!hasLocation) {
                    throw new DomainException(
                            DomainException.Category.CONFLICT,
                            "COURIER_LOCATION_UNKNOWN",
                            "Send the courier's location before making them AVAILABLE.");
                }
                yield AVAILABLE;
            }
            case BUSY -> throw new IllegalArgumentException("only the system sets a courier BUSY");
        };
    }
}
