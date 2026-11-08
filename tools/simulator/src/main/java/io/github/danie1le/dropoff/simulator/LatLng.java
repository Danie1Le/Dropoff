package io.github.danie1le.dropoff.simulator;

/** A point on the map in degrees. */
record LatLng(double lat, double lng) {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    /** Great-circle distance by the haversine formula. */
    double distanceMeters(LatLng other) {
        double dLat = Math.toRadians(other.lat - lat);
        double dLng = Math.toRadians(other.lng - lng);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }

    /**
     * The point {@code meters} along the straight line to {@code target}, or the target itself when it is that close.
     * Interpolating degrees is exact enough over the few kilometres of one city.
     */
    LatLng moveToward(LatLng target, double meters) {
        double distance = distanceMeters(target);
        if (meters >= distance) {
            return target;
        }
        double fraction = meters / distance;
        return new LatLng(lat + (target.lat - lat) * fraction, lng + (target.lng - lng) * fraction);
    }
}
