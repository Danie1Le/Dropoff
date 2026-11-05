package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.order.Actor;
import java.util.EnumSet;
import java.util.Set;

/**
 * A request to move an order to a new status (tickets #3, #10, #13). Each command has one target status, the statuses
 * it may start from, and the actors allowed to send it.
 */
enum OrderCommand {
    ACCEPT,
    START_PREPARING,
    MARK_READY,
    PICK_UP,
    DELIVER,
    CANCEL;

    /** The status this command produces. */
    OrderStatus target() {
        return switch (this) {
            case ACCEPT -> OrderStatus.ACCEPTED;
            case START_PREPARING -> OrderStatus.PREPARING;
            case MARK_READY -> OrderStatus.READY;
            case PICK_UP -> OrderStatus.PICKED_UP;
            case DELIVER -> OrderStatus.DELIVERED;
            case CANCEL -> OrderStatus.CANCELLED;
        };
    }

    /** The statuses this command may start from; {@link OrderStatusRules} adds the SYSTEM limit. */
    Set<OrderStatus> sources() {
        return switch (this) {
            case ACCEPT -> EnumSet.of(OrderStatus.PLACED);
            case START_PREPARING -> EnumSet.of(OrderStatus.ACCEPTED);
            case MARK_READY -> EnumSet.of(OrderStatus.PREPARING);
            case PICK_UP -> EnumSet.of(OrderStatus.READY);
            case DELIVER -> EnumSet.of(OrderStatus.PICKED_UP);
            case CANCEL -> EnumSet.of(OrderStatus.PLACED, OrderStatus.ACCEPTED);
        };
    }

    /** The actors allowed to send this command. */
    Set<Actor> actors() {
        return switch (this) {
            case ACCEPT, START_PREPARING, MARK_READY -> EnumSet.of(Actor.MERCHANT);
            case PICK_UP, DELIVER -> EnumSet.of(Actor.COURIER);
            case CANCEL -> EnumSet.of(Actor.CUSTOMER, Actor.MERCHANT, Actor.SYSTEM);
        };
    }
}
