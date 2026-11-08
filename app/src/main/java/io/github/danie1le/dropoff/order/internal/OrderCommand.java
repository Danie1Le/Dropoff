package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.contracts.Actor;
import java.util.Set;

/**
 * A request to move an order to a new status (tickets #3, #10, #13). Each command has one target status, the statuses
 * it may start from, and the actors allowed to send it.
 */
enum OrderCommand {
    ACCEPT(OrderStatus.ACCEPTED, Set.of(OrderStatus.PLACED), Set.of(Actor.MERCHANT)),
    START_PREPARING(OrderStatus.PREPARING, Set.of(OrderStatus.ACCEPTED), Set.of(Actor.MERCHANT)),
    MARK_READY(OrderStatus.READY, Set.of(OrderStatus.PREPARING), Set.of(Actor.MERCHANT)),
    PICK_UP(OrderStatus.PICKED_UP, Set.of(OrderStatus.READY), Set.of(Actor.COURIER)),
    DELIVER(OrderStatus.DELIVERED, Set.of(OrderStatus.PICKED_UP), Set.of(Actor.COURIER)),
    CANCEL(
            OrderStatus.CANCELLED,
            Set.of(OrderStatus.PLACED, OrderStatus.ACCEPTED),
            Set.of(Actor.CUSTOMER, Actor.MERCHANT, Actor.SYSTEM));

    private final OrderStatus target;

    @SuppressWarnings("ImmutableEnumChecker") // always a Set.of, which is immutable
    private final Set<OrderStatus> sources;

    @SuppressWarnings("ImmutableEnumChecker") // always a Set.of, which is immutable
    private final Set<Actor> actors;

    OrderCommand(OrderStatus target, Set<OrderStatus> sources, Set<Actor> actors) {
        this.target = target;
        this.sources = sources;
        this.actors = actors;
    }

    /** The status this command produces. */
    OrderStatus target() {
        return target;
    }

    /** The statuses this command may start from; {@link OrderStatusRules} adds the SYSTEM limit. */
    Set<OrderStatus> sources() {
        return sources;
    }

    /** The actors allowed to send this command. */
    Set<Actor> actors() {
        return actors;
    }
}
