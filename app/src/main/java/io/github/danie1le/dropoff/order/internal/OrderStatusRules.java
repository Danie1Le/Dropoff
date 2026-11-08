package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.contracts.Actor;

/**
 * The order status rules in pure Java (tickets #3, #10, #13). PLACED → ACCEPTED → PREPARING → READY → PICKED_UP →
 * DELIVERED; CANCELLED only from PLACED or ACCEPTED, and the system cancels only orders still PLACED. A command for the
 * status the order is already in is a no-op; any other illegal step is a 409 {@code INVALID_STATUS_TRANSITION}.
 */
final class OrderStatusRules {

    private OrderStatusRules() {}

    /**
     * Decides what {@code command} does to an order in {@code current}.
     *
     * @throws IllegalArgumentException if {@code actor} may never send {@code command}; callers pick the actor from the
     *     endpoint, so this is a programming error, not a client error
     * @throws DomainException {@code INVALID_STATUS_TRANSITION} if the step is not allowed from {@code current}
     */
    static StatusDecision decide(OrderStatus current, OrderCommand command, Actor actor) {
        if (!command.actors().contains(actor)) {
            throw new IllegalArgumentException(actor + " may not send " + command);
        }
        if (current == command.target()) {
            return StatusDecision.NO_OP;
        }
        boolean beyondSystemLimit = actor == Actor.SYSTEM && current != OrderStatus.PLACED;
        if (!command.sources().contains(current) || beyondSystemLimit) {
            throw new DomainException(
                    DomainException.Category.CONFLICT,
                    "INVALID_STATUS_TRANSITION",
                    "The order's current status does not allow this step.");
        }
        return StatusDecision.MOVE;
    }
}
