package io.github.danie1le.dropoff.dispatch.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picks which couriers dispatch tries for an order, and in what order (M3-2, decisions #11, #13). Pure, so the rules
 * are unit tested without Redis or Postgres.
 */
final class CandidateSelection {

    /** A courier who had an offer for the order this recently is skipped (decision #13). */
    static final Duration EXCLUSION_WINDOW = Duration.ofMinutes(5);

    private CandidateSelection() {}

    /** A courier the geo search found within the dispatch radius. */
    record NearbyCourier(long courierId, double distanceKm) {}

    /** An earlier offer for the same order, in any status. */
    record PastOffer(long courierId, Instant offeredAt) {}

    /**
     * The couriers to try, nearest first (ties by courier id, so the order is stable), each once. A courier offered this
     * order after {@code now - }{@link #EXCLUSION_WINDOW} is left out; an older offer no longer counts.
     */
    static List<Long> select(List<NearbyCourier> nearby, List<PastOffer> pastOffers, Instant now) {
        Instant cutoff = now.minus(EXCLUSION_WINDOW);
        Set<Long> excluded = pastOffers.stream()
                .filter(offer -> offer.offeredAt().isAfter(cutoff))
                .map(PastOffer::courierId)
                .collect(Collectors.toSet());
        return nearby.stream()
                .sorted(Comparator.comparingDouble(NearbyCourier::distanceKm)
                        .thenComparingLong(NearbyCourier::courierId))
                .map(NearbyCourier::courierId)
                .filter(id -> !excluded.contains(id))
                .distinct()
                .toList();
    }
}
