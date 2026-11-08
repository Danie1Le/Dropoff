package io.github.danie1le.dropoff.order.internal;

import java.time.Instant;

/** The STOMP {@code LOCATION} message on {@code /topic/orders/{id}} (#12, #42): the order's courier is at lat/lng. */
record OrderLocationMessage(String type, long orderId, long courierId, double lat, double lng, Instant at) {

    static final String TYPE = "LOCATION";

    OrderLocationMessage(long orderId, long courierId, double lat, double lng, Instant at) {
        this(TYPE, orderId, courierId, lat, lng, at);
    }
}
