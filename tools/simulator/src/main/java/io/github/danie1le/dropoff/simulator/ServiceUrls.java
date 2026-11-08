package io.github.danie1le.dropoff.simulator;

import java.net.URI;

/**
 * Where each service answers (M5-4, ADR-0007). The services split one at a time, so for now merchant and order calls
 * both go to the app on 8080, and only dispatch has its own port. M5-5 moves merchant to 8081 and M5-6 order to 8082.
 * Order-service owns the merchant's order list and steps ({@code /merchants/{id}/orders/...}) and a courier's pick-up
 * and delivery; dispatch-service owns couriers, their locations and offers.
 */
record ServiceUrls(URI merchant, URI order, URI dispatch) {

    static final ServiceUrls LOCAL = new ServiceUrls(
            URI.create("http://localhost:8080"),
            URI.create("http://localhost:8080"),
            URI.create("http://localhost:8083"));
}
