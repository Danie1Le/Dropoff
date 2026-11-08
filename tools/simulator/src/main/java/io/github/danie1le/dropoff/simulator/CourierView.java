package io.github.danie1le.dropoff.simulator;

import org.jspecify.annotations.Nullable;

/** A courier as {@code GET /couriers/{id}} returns it; {@code position} is null before the first location. */
record CourierView(String status, @Nullable LatLng position) {}
