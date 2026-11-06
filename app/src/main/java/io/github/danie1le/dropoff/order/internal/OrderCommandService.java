package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.merchant.MerchantDirectory;
import io.github.danie1le.dropoff.merchant.MerchantDirectory.MerchantView;
import io.github.danie1le.dropoff.order.Actor;
import io.github.danie1le.dropoff.order.OrderCancelled;
import io.github.danie1le.dropoff.order.OrderReady;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies path-scoped status commands (ADR-0005). The status write is a conditional {@code UPDATE …
 * WHERE status IN (sources)}, so of two racing commands only one moves the order; the loser re-reads and becomes a
 * no-op or a 409.
 */
@Service
class OrderCommandService {

    private final OrderRepository orders;
    private final MerchantDirectory merchants;
    private final JdbcClient jdbc;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrderCommandService(
            OrderRepository orders,
            MerchantDirectory merchants,
            JdbcClient jdbc,
            EntityManager entityManager,
            ApplicationEventPublisher events,
            Clock clock) {
        this.orders = orders;
        this.merchants = merchants;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.events = events;
        this.clock = clock;
    }

    /** A merchant command on one of its own orders; another merchant's order is a 404. */
    @Transactional
    OrderResponse byMerchant(long merchantId, long orderId, OrderCommand command) {
        return OrderResponse.of(apply(orderId, o -> o == merchantId, command, Actor.MERCHANT));
    }

    /** A customer command on one of their own orders; another customer's order is a 404. */
    @Transactional
    OrderResponse byCustomer(long customerId, long orderId, OrderCommand command) {
        return OrderResponse.of(apply(orderId, c -> c == customerId, command, Actor.CUSTOMER));
    }

    /**
     * Cancels, as {@link Actor#SYSTEM}, the oldest order still PLACED before {@code cutoff} (decisions #13, #14). The
     * row is locked with {@code SKIP LOCKED}, so concurrent runs each take a different order.
     *
     * @return whether an order was cancelled; {@code false} means none is left to cancel
     */
    @Transactional
    boolean cancelOldestPlacedBefore(Instant cutoff) {
        Optional<Long> id = jdbc.sql("SELECT id FROM orders WHERE status = 'PLACED' AND created_at < :cutoff"
                        + " ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED")
                .param("cutoff", Timestamp.from(cutoff))
                .query(Long.class)
                .optional();
        id.ifPresent(orderId -> apply(orderId, owner -> true, OrderCommand.CANCEL, Actor.SYSTEM));
        return id.isPresent();
    }

    private Order apply(long orderId, LongPredicate owns, OrderCommand command, Actor actor) {
        Order order = orders.findById(orderId).orElseThrow(() -> notFound(orderId));
        boolean owned = switch (actor) {
            case MERCHANT -> owns.test(order.merchantId());
            case CUSTOMER -> owns.test(order.customerId());
            case SYSTEM -> true;
            case COURIER -> throw new IllegalStateException("courier commands are not built yet (M3)");
        };
        if (!owned) {
            throw notFound(orderId);
        }
        if (OrderStatusRules.decide(order.status(), command, actor) instanceof StatusDecision.NoOp) {
            return order;
        }
        // Postgres keeps microseconds; truncate so events match what is stored.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // Conditional update: the FOR UPDATE sub-select waits for a racing command and re-reads the row, so only the
        // first command moves the order, and RETURNING gives the status it really left.
        Optional<String> from = jdbc.sql("UPDATE orders o SET status = :to, updated_at = :now"
                        + " FROM (SELECT id, status FROM orders WHERE id = :id FOR UPDATE) old"
                        + " WHERE o.id = old.id AND old.status IN (:from) RETURNING old.status")
                .param("to", command.target().name())
                .param("now", Timestamp.from(now))
                .param("id", orderId)
                .param("from", command.sources().stream().map(Enum::name).toList())
                .query(String.class)
                .optional();
        entityManager.refresh(order);
        if (from.isEmpty()) {
            // Lost a race: the order moved after we read it. Re-decide against what is there now (no-op or 409).
            OrderStatusRules.decide(order.status(), command, actor);
            return order;
        }
        OrderEventLog.record(jdbc, orderId, from.get(), command.target(), actor, now);
        publish(order, command, actor, now);
        return order;
    }

    private void publish(Order order, OrderCommand command, Actor actor, Instant now) {
        switch (command) {
            case CANCEL ->
                events.publishEvent(new OrderCancelled(UUID.randomUUID(), order.id(), order.merchantId(), actor, now));
            case MARK_READY -> {
                MerchantView merchant = merchants
                        .findMerchant(order.merchantId())
                        .orElseThrow(() -> new IllegalStateException("order's merchant is gone"));
                Location delivery = order.deliveryAddress();
                events.publishEvent(new OrderReady(
                        UUID.randomUUID(),
                        order.id(),
                        merchant.id(),
                        merchant.name(),
                        merchant.address(),
                        merchant.lat(),
                        merchant.lng(),
                        delivery.address(),
                        delivery.lat(),
                        delivery.lng(),
                        now,
                        now));
            }
            default -> {}
        }
    }

    static DomainException notFound(long orderId) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "ORDER_NOT_FOUND", "Order " + orderId + " does not exist.");
    }
}
