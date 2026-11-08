package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.contracts.Actor;
import java.time.Instant;

/** The STOMP {@code STATUS} message on {@code /topic/orders/{id}} (#42): the order moved to {@code status}. */
record OrderStatusMessage(String type, long orderId, OrderStatus status, Actor actor, Instant at) {

    static final String TYPE = "STATUS";

    OrderStatusMessage(long orderId, OrderStatus status, Actor actor, Instant at) {
        this(TYPE, orderId, status, actor, at);
    }
}
