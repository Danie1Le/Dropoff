package io.github.danie1le.dropoff.dispatch.internal;

/** Where an offer to one courier stands (ticket #11, GLOSSARY: Offer). The V11 CHECK repeats these values. */
enum OfferStatus {
    /** Sent; the courier has until {@code expires_at} to respond. */
    OFFERED,
    /** The courier took the order. */
    ACCEPTED,
    /** The courier turned it down. */
    DECLINED,
    /** Nobody responded in time (M3-4). */
    TIMED_OUT,
    /**
     * Closed by dispatch before the courier answered: the order went back to the courier who had already picked it up
     * (ticket #85).
     */
    WITHDRAWN
}
