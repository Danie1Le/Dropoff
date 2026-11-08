package io.github.danie1le.dropoff.simulator;

import java.time.Instant;

/** The part of {@code GET /couriers/{id}/offers/current} a courier bot needs. */
record Offer(long offerId, long orderId, LatLng pickup, LatLng delivery, Instant expiresAt) {}
