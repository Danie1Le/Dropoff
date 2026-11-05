package io.github.danie1le.dropoff.order.internal;

/** Who changed an order's status (ticket #10). Names match the {@code order_events.actor} CHECK. */
enum Actor {
    CUSTOMER,
    MERCHANT,
    COURIER,
    SYSTEM
}
