package io.github.danie1le.dropoff.order.internal;

/** The outcome of applying an {@link OrderCommand} to an order's current status. */
enum StatusDecision {

    /** The order moves: write the new status and an {@code order_events} row, and publish any event. */
    MOVE,

    /** The order is already in the target status: return it unchanged, with no event row and no event (#10). */
    NO_OP
}
