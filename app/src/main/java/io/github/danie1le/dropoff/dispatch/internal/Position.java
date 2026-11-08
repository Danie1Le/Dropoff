package io.github.danie1le.dropoff.dispatch.internal;

/** A point on the map in degrees. Couriers have no street address, only coordinates. */
record Position(double lat, double lng) {}
