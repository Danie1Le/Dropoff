package io.github.danie1le.dropoff.simulator;

import java.net.URI;

/**
 * Where each service answers (M5-4, ADR-0007): merchant on 8081, order on 8082 (M5-6) and dispatch on 8083.
 * Order-service owns the merchant's order list and steps ({@code /merchants/{id}/orders/...}) and a courier's pick-up
 * and delivery; dispatch-service owns couriers, their locations and offers; merchant-service only creates merchants
 * and menu items, so the simulator needs it for {@code seed} alone.
 */
record ServiceUrls(URI merchant, URI order, URI dispatch) {

    static final ServiceUrls LOCAL = new ServiceUrls(
            URI.create("http://localhost:8081"),
            URI.create("http://localhost:8082"),
            URI.create("http://localhost:8083"));
}
