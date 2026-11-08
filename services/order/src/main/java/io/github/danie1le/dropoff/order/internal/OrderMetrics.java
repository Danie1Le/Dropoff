package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.AfterCommit;
import io.github.danie1le.dropoff.common.CachedSnapshot;
import io.github.danie1le.dropoff.contracts.Actor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * order-service's own metrics (M6-1, tickets #13, #34, #42).
 *
 * <ul>
 *   <li>{@code dropoff_orders_placed_total}, {@code dropoff_orders_cancelled_total{actor}} and {@code
 *       dropoff_orders_delivered_total}: counters, moved by {@link OrderEventLog} once the status change commits.
 *   <li>{@code dropoff_orders_in_preparation} and {@code dropoff_oldest_in_preparation_age_seconds}: gauges for orders
 *       the merchant accepted but has not marked READY. Decision #13 takes no automatic action on them; this is where
 *       they show. The age counts from the ACCEPTED event. Both come from one query, cached for {@code
 *       dropoff.metrics.gauge-max-age} (15 s, the Prometheus scrape interval).
 * </ul>
 */
@Component
class OrderMetrics {

    /** The actors that can cancel an order: customer and merchant, or SYSTEM for an auto-cancel. */
    private static final List<Actor> CANCELLING = List.of(Actor.CUSTOMER, Actor.MERCHANT, Actor.SYSTEM);

    private final Counter placed;
    private final Counter delivered;
    private final Map<Actor, Counter> cancelled = new EnumMap<>(Actor.class);

    OrderMetrics(
            MeterRegistry meters,
            JdbcClient jdbc,
            Clock clock,
            @Value("${dropoff.metrics.gauge-max-age:15s}") Duration gaugeMaxAge) {
        placed = Counter.builder("dropoff.orders.placed")
                .description("Orders placed")
                .register(meters);
        delivered = Counter.builder("dropoff.orders.delivered")
                .description("Orders delivered")
                .register(meters);
        for (Actor actor : CANCELLING) {
            cancelled.put(
                    actor,
                    Counter.builder("dropoff.orders.cancelled")
                            .description("Orders cancelled, by who cancelled them")
                            .tag("actor", actor.name())
                            .register(meters));
        }

        // Gauges hold their state object weakly; strongReference keeps this local snapshot alive for the app's life.
        CachedSnapshot<InPreparation> snapshot = new CachedSnapshot<>(() -> inPreparation(jdbc), gaugeMaxAge, clock);
        Gauge.builder("dropoff.orders.in.preparation", snapshot, s -> s.read(InPreparation::orders))
                .description("Orders ACCEPTED or PREPARING")
                .strongReference(true)
                .register(meters);
        Gauge.builder("dropoff.oldest.in.preparation.age", snapshot, s -> s.read(InPreparation::oldestAgeSeconds))
                .description("How long the oldest order in preparation has been accepted")
                .strongReference(true)
                .baseUnit("seconds")
                .register(meters);
    }

    /** Counts the change once the current transaction commits, so a rolled-back change is never counted. */
    void statusChanged(OrderStatus to, Actor actor) {
        Counter counter = switch (to) {
            case PLACED -> placed;
            case DELIVERED -> delivered;
            case CANCELLED -> cancelled.get(actor);
            default -> null;
        };
        if (counter != null) {
            AfterCommit.run(counter::increment);
        }
    }

    private record InPreparation(long orders, double oldestAgeSeconds) {}

    /**
     * One query for both gauges. The partial index {@code orders_in_preparation_idx} (V2) keeps the scan to the orders
     * in preparation, and each one's ACCEPTED event is found through {@code order_events_order_id_idx}.
     */
    private static InPreparation inPreparation(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(*) AS orders,"
                        + " coalesce(extract(epoch FROM now() - min(accepted_at)), 0) AS oldest_age"
                        + " FROM (SELECT (SELECT max(e.created_at) FROM order_events e"
                        + " WHERE e.order_id = o.id AND e.to_status = 'ACCEPTED') AS accepted_at"
                        + " FROM orders o WHERE o.status IN ('ACCEPTED', 'PREPARING')) p")
                .query((rs, row) -> new InPreparation(rs.getLong("orders"), rs.getDouble("oldest_age")))
                .single();
    }
}
