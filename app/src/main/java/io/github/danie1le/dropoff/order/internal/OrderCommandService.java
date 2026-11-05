package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.merchant.MerchantCatalog;
import io.github.danie1le.dropoff.merchant.MerchantCatalog.MerchantView;
import io.github.danie1le.dropoff.order.OrderCancelled;
import io.github.danie1le.dropoff.order.OrderReady;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads orders (as response bodies, built inside the transaction because lines load lazily) and applies path-scoped status commands (ADR-0005). The status write is a conditional {@code UPDATE …
 * WHERE status IN (sources)}, so of two racing commands only one moves the order; the loser re-reads and becomes a
 * no-op or a 409.
 */
@Service
class OrderCommandService {

    private final OrderRepository orders;
    private final MerchantCatalog catalog;
    private final JdbcClient jdbc;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    OrderCommandService(
            OrderRepository orders,
            MerchantCatalog catalog,
            JdbcClient jdbc,
            EntityManager entityManager,
            ApplicationEventPublisher events) {
        this.orders = orders;
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.events = events;
    }

    @Transactional(readOnly = true)
    OrderResponse get(long orderId) {
        return OrderResponse.of(orders.findById(orderId).orElseThrow(() -> notFound(orderId)));
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

    private Order apply(long orderId, LongPredicate owns, OrderCommand command, Actor actor) {
        Order order = orders.findById(orderId).orElseThrow(() -> notFound(orderId));
        long owner = actor == Actor.MERCHANT ? order.merchantId() : order.customerId();
        if (!owns.test(owner)) {
            throw notFound(orderId);
        }
        if (OrderStatusRules.decide(order.status(), command, actor) instanceof StatusDecision.NoOp) {
            return order;
        }
        Instant now = Instant.now();
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
        jdbc.sql("INSERT INTO order_events (order_id, from_status, to_status, actor, created_at)"
                        + " VALUES (:id, :from, :to, :actor, :now)")
                .param("id", orderId)
                .param("from", from.get())
                .param("to", command.target().name())
                .param("actor", actor.name())
                .param("now", Timestamp.from(now))
                .update();
        publish(order, command, actor, now);
        return order;
    }

    private void publish(Order order, OrderCommand command, Actor actor, Instant now) {
        switch (command) {
            case CANCEL ->
                events.publishEvent(
                        new OrderCancelled(UUID.randomUUID(), order.id(), order.merchantId(), actor.name(), now));
            case MARK_READY -> {
                MerchantView merchant = catalog.findMerchant(order.merchantId())
                        .orElseThrow(() -> new IllegalStateException("order's merchant is gone"));
                Location to = order.deliveryAddress();
                events.publishEvent(new OrderReady(
                        UUID.randomUUID(),
                        order.id(),
                        merchant.id(),
                        merchant.name(),
                        merchant.address(),
                        merchant.lat(),
                        merchant.lng(),
                        to.address(),
                        to.lat(),
                        to.lng(),
                        now,
                        now));
            }
            default -> {}
        }
    }

    private static DomainException notFound(long orderId) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "ORDER_NOT_FOUND", "Order " + orderId + " does not exist.");
    }
}
