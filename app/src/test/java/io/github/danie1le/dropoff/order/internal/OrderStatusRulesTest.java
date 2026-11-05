package io.github.danie1le.dropoff.order.internal;

import static io.github.danie1le.dropoff.order.internal.Actor.COURIER;
import static io.github.danie1le.dropoff.order.internal.Actor.CUSTOMER;
import static io.github.danie1le.dropoff.order.internal.Actor.MERCHANT;
import static io.github.danie1le.dropoff.order.internal.Actor.SYSTEM;
import static io.github.danie1le.dropoff.order.internal.OrderCommand.ACCEPT;
import static io.github.danie1le.dropoff.order.internal.OrderCommand.CANCEL;
import static io.github.danie1le.dropoff.order.internal.OrderCommand.DELIVER;
import static io.github.danie1le.dropoff.order.internal.OrderCommand.MARK_READY;
import static io.github.danie1le.dropoff.order.internal.OrderCommand.PICK_UP;
import static io.github.danie1le.dropoff.order.internal.OrderCommand.START_PREPARING;
import static io.github.danie1le.dropoff.order.internal.OrderStatus.ACCEPTED;
import static io.github.danie1le.dropoff.order.internal.OrderStatus.CANCELLED;
import static io.github.danie1le.dropoff.order.internal.OrderStatus.DELIVERED;
import static io.github.danie1le.dropoff.order.internal.OrderStatus.PICKED_UP;
import static io.github.danie1le.dropoff.order.internal.OrderStatus.PLACED;
import static io.github.danie1le.dropoff.order.internal.OrderStatus.PREPARING;
import static io.github.danie1le.dropoff.order.internal.OrderStatus.READY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.danie1le.dropoff.common.DomainException;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Order status rules (tickets #3 and #10): every legal and illegal step, the actor rules, and repeat = no-op. */
class OrderStatusRulesTest {

    static Stream<Arguments> legalSteps() {
        return Stream.of(
                Arguments.of(PLACED, ACCEPT, MERCHANT, ACCEPTED),
                Arguments.of(ACCEPTED, START_PREPARING, MERCHANT, PREPARING),
                Arguments.of(PREPARING, MARK_READY, MERCHANT, READY),
                Arguments.of(READY, PICK_UP, COURIER, PICKED_UP),
                Arguments.of(PICKED_UP, DELIVER, COURIER, DELIVERED),
                Arguments.of(PLACED, CANCEL, CUSTOMER, CANCELLED),
                Arguments.of(PLACED, CANCEL, MERCHANT, CANCELLED),
                Arguments.of(PLACED, CANCEL, SYSTEM, CANCELLED),
                Arguments.of(ACCEPTED, CANCEL, CUSTOMER, CANCELLED),
                Arguments.of(ACCEPTED, CANCEL, MERCHANT, CANCELLED));
    }

    @ParameterizedTest
    @MethodSource("legalSteps")
    void legalStepMovesTheOrder(OrderStatus from, OrderCommand command, Actor actor, OrderStatus to) {
        assertThat(OrderStatusRules.decide(from, command, actor)).isEqualTo(new StatusDecision.Move(from, to));
    }

    static Stream<Arguments> illegalSteps() {
        Set<String> legal = Set.of(
                "PLACED/ACCEPT",
                "ACCEPTED/START_PREPARING",
                "PREPARING/MARK_READY",
                "READY/PICK_UP",
                "PICKED_UP/DELIVER",
                "PLACED/CANCEL",
                "ACCEPTED/CANCEL");
        Stream.Builder<Arguments> steps = Stream.builder();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderCommand command : OrderCommand.values()) {
                boolean repeat = from == command.target();
                if (!repeat && !legal.contains(from + "/" + command)) {
                    for (Actor actor : command.actors()) {
                        steps.add(Arguments.of(from, command, actor));
                    }
                }
            }
        }
        steps.add(Arguments.of(ACCEPTED, CANCEL, SYSTEM)); // the system only cancels orders never accepted (#13)
        return steps.build();
    }

    @ParameterizedTest
    @MethodSource("illegalSteps")
    void illegalStepIsAnInvalidStatusTransition(OrderStatus from, OrderCommand command, Actor actor) {
        assertThatThrownBy(() -> OrderStatusRules.decide(from, command, actor))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.category()).isEqualTo(DomainException.Category.CONFLICT);
                    assertThat(e.code()).isEqualTo("INVALID_STATUS_TRANSITION");
                });
    }

    @Test
    void illegalStepsCoverTheWholeTable() {
        // 7 statuses x 6 commands = 42 pairs; 7 legal, 6 repeats, so 29 illegal pairs, plus ACCEPTED/CANCEL for SYSTEM.
        assertThat(illegalSteps().map(a -> a.get()[0] + "/" + a.get()[1]).distinct())
                .hasSize(30);
    }

    static Stream<Arguments> wrongActors() {
        Stream.Builder<Arguments> steps = Stream.builder();
        for (OrderCommand command : OrderCommand.values()) {
            for (Actor actor : EnumSet.complementOf(EnumSet.copyOf(command.actors()))) {
                steps.add(Arguments.of(command, actor));
            }
        }
        return steps.build();
    }

    @ParameterizedTest
    @MethodSource("wrongActors")
    void onlyTheRightActorMayTakeAStep(OrderCommand command, Actor actor) {
        for (OrderStatus from : OrderStatus.values()) {
            assertThatThrownBy(() -> OrderStatusRules.decide(from, command, actor))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void actorPerStep() {
        assertThat(ACCEPT.actors()).containsExactly(MERCHANT);
        assertThat(START_PREPARING.actors()).containsExactly(MERCHANT);
        assertThat(MARK_READY.actors()).containsExactly(MERCHANT);
        assertThat(PICK_UP.actors()).containsExactly(COURIER);
        assertThat(DELIVER.actors()).containsExactly(COURIER);
        assertThat(CANCEL.actors()).containsExactlyInAnyOrder(CUSTOMER, MERCHANT, SYSTEM);
    }

    static Stream<Arguments> repeats() {
        return Stream.of(
                Arguments.of(ACCEPTED, ACCEPT, MERCHANT),
                Arguments.of(PREPARING, START_PREPARING, MERCHANT),
                Arguments.of(READY, MARK_READY, MERCHANT),
                Arguments.of(PICKED_UP, PICK_UP, COURIER),
                Arguments.of(DELIVERED, DELIVER, COURIER),
                Arguments.of(CANCELLED, CANCEL, CUSTOMER),
                Arguments.of(CANCELLED, CANCEL, MERCHANT),
                Arguments.of(CANCELLED, CANCEL, SYSTEM));
    }

    @ParameterizedTest
    @MethodSource("repeats")
    void repeatedCommandIsANoOp(OrderStatus current, OrderCommand command, Actor actor) {
        assertThat(OrderStatusRules.decide(current, command, actor)).isEqualTo(new StatusDecision.NoOp(current));
    }

    @Test
    void stepsTheStatusMaySkipAreIllegal() {
        assertThatThrownBy(() -> OrderStatusRules.decide(PLACED, MARK_READY, MERCHANT))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> OrderStatusRules.decide(READY, CANCEL, CUSTOMER))
                .isInstanceOf(DomainException.class);
    }
}
