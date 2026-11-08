package io.github.danie1le.dropoff.simulator;

import java.net.URI;

/**
 * Where each service answers (M5-4, ADR-0007). The services split one at a time: merchant has its own port since M5-5
 * (8081) and dispatch since M5-4 (8083), and order calls still go to the app on 8080 until M5-6 moves it to 8082.
 * Order-service owns the merchant's order list and steps ({@code /merchants/{id}/orders/...}) and a courier's pick-up
 * and delivery; dispatch-service owns couriers, their locations and offers; merchant-service only creates merchants
 * and menu items, so the simulator needs it for {@code seed} alone.
 */
record ServiceUrls(URI merchant, URI order, URI dispatch) {

    static final ServiceUrls LOCAL = new ServiceUrls(
            URI.create("http://localhost:8081"),
            URI.create("http://localhost:8080"),
            URI.create("http://localhost:8083"));
}
