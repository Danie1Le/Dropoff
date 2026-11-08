package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.contracts.Actor;
import io.github.danie1le.dropoff.contracts.OrderCancelled;
import io.github.danie1le.dropoff.contracts.OrderReady;
import io.github.danie1le.dropoff.merchant.MerchantDirectory;
import io.github.danie1le.dropoff.merchant.MerchantDirectory.MerchantView;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
        Order order = find(orderId);
        if (order.merchantId() != merchantId) {
            throw notFound(orderId);
        }
        return OrderResponse.of(apply(order, command, Actor.MERCHANT));
    }

    /** A customer command on one of their own orders; another customer's order is a 404. */
    @Transactional
    OrderResponse byCustomer(long customerId, long orderId, OrderCommand command) {
        Order order = find(orderId);
        if (order.customerId() != customerId) {
            throw notFound(orderId);
        }
        return OrderResponse.of(apply(order, command, Actor.CUSTOMER));
    }

    /**
     * Cancels, as {@link Actor#SYSTEM}, the oldest order still PLACED before {@code cutoff} and not in {@code tried}
     * (decisions #13, #14). The row is locked with {@code SKIP LOCKED}, so concurrent runs each take a different order.
     *
     * <p>The picked id is added to {@code tried} before the cancel runs, so if the cancel throws (and rolls back) the
     * caller still knows which order failed and the next call steps past it.
     *
     * @return whether an order was cancelled; {@code false} means none is left to cancel
     */
    @Transactional
    boolean cancelOldestPlacedBefore(Instant cutoff, Set<Long> tried) {
        String skipTried = tried.isEmpty() ? "" : " AND id NOT IN (:tried)";
        JdbcClient.StatementSpec select = jdbc.sql(
                        "SELECT id FROM orders WHERE status = 'PLACED' AND created_at < :cutoff" + skipTried
                                + " ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED")
                .param("cutoff", Timestamp.from(cutoff));
        if (!tried.isEmpty()) {
            select = select.param("tried", List.copyOf(tried));
        }
        Optional<Long> id = select.query(Long.class).optional();
        if (id.isEmpty()) {
            return false;
        }
        tried.add(id.get());
        apply(find(id.get()), OrderCommand.CANCEL, Actor.SYSTEM);
        return true;
    }

    private Order find(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> notFound(orderId));
    }

    /** Applies {@code command} to an order the caller has already checked {@code actor} may act on. */
    private Order apply(Order order, OrderCommand command, Actor actor) {
        long orderId = order.id();
        if (OrderStatusRules.decide(order.status(), command, actor) == StatusDecision.NO_OP) {
            return order;
        }
        Instant now = DbClock.now(clock);
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
