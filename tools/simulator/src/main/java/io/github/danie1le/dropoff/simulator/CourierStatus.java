package io.github.danie1le.dropoff.simulator;

/** A courier's status as the API names it (ticket #42). A bot may set only {@code AVAILABLE} or {@code OFFLINE}. */
enum CourierStatus {
    AVAILABLE,
    BUSY,
    OFFLINE
}
