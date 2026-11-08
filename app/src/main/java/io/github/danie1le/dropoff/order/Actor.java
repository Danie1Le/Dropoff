package io.github.danie1le.dropoff.order;

/** Who changed an order's status (ticket #10). Names match the {@code order_events.actor} CHECK. */
public enum Actor {
    CUSTOMER,
    MERCHANT,
    COURIER,
    SYSTEM
}
