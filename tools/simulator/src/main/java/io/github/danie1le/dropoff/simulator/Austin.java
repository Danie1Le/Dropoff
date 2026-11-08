package io.github.danie1le.dropoff.simulator;

import java.util.Random;

/** The one demo city (ticket #33): every seeded point lies within 8 km of downtown Austin, TX. */
final class Austin {

    static final LatLng DOWNTOWN = new LatLng(30.2672, -97.7431);
    static final double RADIUS_METERS = 8_000;

    private static final double METERS_PER_DEGREE_LAT = 111_320;

    private Austin() {}

    /** A uniformly random point in the 8 km disc around downtown. */
    static LatLng randomPoint(Random random) {
        return randomPoint(random, RADIUS_METERS);
    }

    /** A uniformly random point within {@code radiusMeters} of downtown. */
    static LatLng randomPoint(Random random, double radiusMeters) {
        // sqrt keeps the density even across the disc instead of bunching points near the centre.
        double radius = radiusMeters * Math.sqrt(random.nextDouble());
        double angle = 2 * Math.PI * random.nextDouble();
        double dLat = radius * Math.cos(angle) / METERS_PER_DEGREE_LAT;
        double dLng = radius * Math.sin(angle) / (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(DOWNTOWN.lat())));
        return new LatLng(DOWNTOWN.lat() + dLat, DOWNTOWN.lng() + dLng);
    }
}
