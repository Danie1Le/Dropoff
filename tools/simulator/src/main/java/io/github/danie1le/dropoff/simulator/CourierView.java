package io.github.danie1le.dropoff.simulator;

import org.jspecify.annotations.Nullable;

/** A courier as {@code GET /couriers/{id}} returns it; {@code position} is null before the first location. */
record CourierView(CourierStatus status, @Nullable LatLng position) {}
