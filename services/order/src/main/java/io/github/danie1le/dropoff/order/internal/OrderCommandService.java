package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.Actor;
import io.github.danie1le.dropoff.contracts.OrderCancelled;
import io.github.danie1le.dropoff.contracts.OrderDelivered;
import io.github.danie1le.dropoff.contracts.OrderPickedUp;
import io.github.danie1le.dropoff.contracts.OrderReady;
import io.github.danie1le.dropoff.order.internal.MerchantCopies.MerchantCopy;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import io.github.danie1le.dropoff.web.DomainException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
    private final MerchantCopies merchants;
    private final JdbcClient jdbc;
    private final OrderEventLog eventLog;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrderCommandService(
            OrderRepository orders,
            MerchantCopies merchants,
            JdbcClient jdbc,
            OrderEventLog eventLog,
            EntityManager entityManager,
            ApplicationEventPublisher events,
            Clock clock) {
        this.orders = orders;
        this.merchants = merchants;
        this.jdbc = jdbc;
        this.eventLog = eventLog;
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
     * A courier command on an order assigned to that courier; any other order, including one with no courier yet, is a
     * 404. The order row is locked before the courier check, so an unassignment (M3-4) cannot slip in between the check
     * and the status write.
     */
    @Transactional
    OrderResponse byCourier(long courierId, long orderId, OrderCommand command) {
        Order order = Optional.ofNullable(entityManager.find(Order.class, orderId, LockModeType.PESSIMISTIC_WRITE))
                .orElseThrow(() -> notFound(orderId));
        if (!Objects.equals(order.courierId(), courierId)) {
            throw notFound(orderId);
        }
        return OrderResponse.of(apply(order, command, Actor.COURIER));
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
        // Conditional update: an UPDATE waits for a racing command's row lock and then re-checks the WHERE against the
        // row that command left (READ COMMITTED), so only the first command moves the order. RETURNING old.status
        // (Postgres 18) gives the status it really left. One index probe; the old self-join with a FOR UPDATE
        // sub-select did the same in two (M7-3, docs/perf/README.md).
        Optional<String> from = jdbc.sql("UPDATE orders SET status = :to, updated_at = :now"
                        + " WHERE id = :id AND status IN (:from) RETURNING old.status")
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
        eventLog.record(orderId, from.get(), command.target(), actor, now);
        publish(order, command, actor, now);
        return order;
    }

    private void publish(Order order, OrderCommand command, Actor actor, Instant now) {
        switch (command) {
            case CANCEL ->
                events.publishEvent(new OrderCancelled(UUID.randomUUID(), order.id(), order.merchantId(), actor, now));
            case MARK_READY -> {
                // The pickup point comes from the order module's copy (ADR-0007), so dispatch needs no merchant data.
                MerchantCopy merchant = merchants
                        .findMerchant(order.merchantId())
                        .orElseThrow(() -> new IllegalStateException("order's merchant has no copy"));
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
            case PICK_UP ->
                events.publishEvent(new OrderPickedUp(UUID.randomUUID(), order.id(), courierOf(order), now));
            case DELIVER ->
                events.publishEvent(new OrderDelivered(UUID.randomUUID(), order.id(), courierOf(order), now));
            default -> {}
        }
    }

    /** The courier of an order a courier command just moved; {@link #byCourier} checked it is set. */
    private static long courierOf(Order order) {
        return Objects.requireNonNull(order.courierId(), "a courier command moved an order with no courier");
    }

    static DomainException notFound(long orderId) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "ORDER_NOT_FOUND", "Order " + orderId + " does not exist.");
    }
}
