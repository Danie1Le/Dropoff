package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.common.AfterCommit;
import io.github.danie1le.dropoff.common.CachedSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * dispatch-service's own metrics (M6-1, tickets #13, #34, #42).
 *
 * <ul>
 *   <li>{@code dropoff_offers_total{outcome}}: how each offer ended (ACCEPTED, DECLINED, TIMED_OUT), counted once the
 *       answer commits.
 *   <li>{@code dropoff_dispatch_duration_seconds}: a timer with histogram buckets, from the order's {@code ready_at} to
 *       the accept (ticket #42 point 1).
 *   <li>{@code dropoff_orders_waiting_for_courier}, {@code dropoff_oldest_waiting_order_age_seconds} and {@code
 *       dropoff_couriers{status}}: gauges from one query, cached for {@code dropoff.metrics.gauge-max-age} (15 s, the
 *       Prometheus scrape interval). The oldest wait counts from {@code ready_at}, the age the 15 min alert of
 *       decision #13 watches.
 * </ul>
 */
@Component
class DispatchMetrics {

    /** The ways an offer ends that #34 counts. WITHDRAWN (the order went away) is not a courier's answer. */
    private static final List<OfferStatus> OUTCOMES =
            List.of(OfferStatus.ACCEPTED, OfferStatus.DECLINED, OfferStatus.TIMED_OUT);

    private final Map<OfferStatus, Counter> offers = new EnumMap<>(OfferStatus.class);
    private final Timer dispatchDuration;

    DispatchMetrics(
            MeterRegistry meters,
            JdbcClient jdbc,
            Clock clock,
            @Value("${dropoff.metrics.gauge-max-age:15s}") Duration gaugeMaxAge) {
        for (OfferStatus outcome : OUTCOMES) {
            offers.put(
                    outcome,
                    Counter.builder("dropoff.offers")
                            .description("Offers answered, by outcome")
                            .tag("outcome", outcome.name())
                            .register(meters));
        }
        dispatchDuration = Timer.builder("dropoff.dispatch.duration")
                .description("From the order being READY to a courier accepting it")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofSeconds(1))
                .maximumExpectedValue(Duration.ofMinutes(30))
                .register(meters);

        // Gauges hold their state object weakly; strongReference keeps this local snapshot alive for the app's life.
        CachedSnapshot<Snapshot> snapshot = new CachedSnapshot<>(() -> snapshot(jdbc), gaugeMaxAge, clock);
        Gauge.builder("dropoff.orders.waiting.for.courier", snapshot, s -> s.read(Snapshot::waiting))
                .description("Orders READY with no courier assigned")
                .strongReference(true)
                .register(meters);
        Gauge.builder("dropoff.oldest.waiting.order.age", snapshot, s -> s.read(Snapshot::oldestWaitSeconds))
                .description("How long the oldest order waiting for a courier has been READY")
                .strongReference(true)
                .baseUnit("seconds")
                .register(meters);
        for (CourierStatus status : CourierStatus.values()) {
            Gauge.builder("dropoff.couriers", snapshot, s -> s.read(c -> c.couriers(status)))
                    .description("Couriers, by status")
                    .strongReference(true)
                    .tag("status", status.name())
                    .register(meters);
        }
    }

    /** Counts how an offer ended once the current transaction commits. */
    void offerAnswered(OfferStatus outcome) {
        Counter counter = offers.get(outcome);
        if (counter != null) {
            AfterCommit.run(counter::increment);
        }
    }

    /** Counts an accepted offer and records how long its order waited, once the accept commits. */
    void accepted(Duration sinceReady) {
        offerAnswered(OfferStatus.ACCEPTED);
        AfterCommit.run(() -> dispatchDuration.record(sinceReady));
    }

    private record Snapshot(long waiting, double oldestWaitSeconds, long offline, long available, long busy) {

        long couriers(CourierStatus status) {
            return switch (status) {
                case OFFLINE -> offline;
                case AVAILABLE -> available;
                case BUSY -> busy;
            };
        }
    }

    /**
     * One query for every gauge. The waiting count and the oldest wait read the partial index {@code
     * dispatch_orders_waiting_ready_at_idx}; the courier counts scan {@code couriers}, which stays small.
     */
    private static Snapshot snapshot(JdbcClient jdbc) {
        return jdbc.sql("SELECT"
                        + " (SELECT count(*) FROM dispatch_orders WHERE status = 'WAITING') AS waiting,"
                        + " (SELECT coalesce(extract(epoch FROM now() - min(ready_at)), 0) FROM dispatch_orders"
                        + " WHERE status = 'WAITING') AS oldest_wait,"
                        + " count(*) FILTER (WHERE status = 'OFFLINE') AS offline,"
                        + " count(*) FILTER (WHERE status = 'AVAILABLE') AS available,"
                        + " count(*) FILTER (WHERE status = 'BUSY') AS busy"
                        + " FROM couriers")
                .query((rs, row) -> new Snapshot(
                        rs.getLong("waiting"),
                        rs.getDouble("oldest_wait"),
                        rs.getLong("offline"),
                        rs.getLong("available"),
                        rs.getLong("busy")))
                .single();
    }
}
