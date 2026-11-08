package io.github.danie1le.dropoff.order.internal;

/** Where an order is in its lifecycle (GLOSSARY: Order Status). Names match the {@code orders.status} CHECK. */
enum OrderStatus {
    PLACED,
    ACCEPTED,
    PREPARING,
    READY,
    PICKED_UP,
    DELIVERED,
    CANCELLED
}
