package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.dispatch.internal.CandidateSelection.NearbyCourier;
import io.github.danie1le.dropoff.dispatch.internal.CandidateSelection.PastOffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which couriers dispatch tries for an order, and in what order (M3-2, decisions #11, #13). */
class CandidateSelectionTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    private static NearbyCourier at(long courierId, double distanceKm) {
        return new NearbyCourier(courierId, distanceKm);
    }

    private static PastOffer offeredAgo(long courierId, Duration ago) {
        return new PastOffer(courierId, NOW.minus(ago));
    }

    @Test
    void triesTheNearestCourierFirst() {
        List<Long> candidates = CandidateSelection.select(List.of(at(3, 2.5), at(1, 0.4), at(2, 1.1)), List.of(), NOW);

        assertThat(candidates).containsExactly(1L, 2L, 3L);
    }

    @Test
    void breaksDistanceTiesByCourierId() {
        List<Long> candidates = CandidateSelection.select(List.of(at(9, 1.0), at(4, 1.0)), List.of(), NOW);

        assertThat(candidates).containsExactly(4L, 9L);
    }

    @Test
    void noNearbyCouriersMeansNoCandidates() {
        assertThat(CandidateSelection.select(List.of(), List.of(), NOW)).isEmpty();
    }

    @Test
    void skipsCouriersOfferedThisOrderInTheLastFiveMinutes() {
        List<Long> candidates = CandidateSelection.select(
                List.of(at(1, 0.4), at(2, 1.1), at(3, 2.5)),
                List.of(offeredAgo(1, Duration.ofSeconds(31)), offeredAgo(3, Duration.ofMinutes(4))),
                NOW);

        assertThat(candidates).containsExactly(2L);
    }

    @Test
    void anOfferExactlyFiveMinutesOldNoLongerExcludes() {
        // The window is offered_at > now - 5 min (decision #13), so the boundary itself is outside it.
        List<Long> candidates = CandidateSelection.select(
                List.of(at(1, 0.4), at(2, 1.1)),
                List.of(
                        offeredAgo(1, Duration.ofMinutes(5)),
                        offeredAgo(2, Duration.ofMinutes(5).minusNanos(1000))),
                NOW);

        assertThat(candidates).containsExactly(1L);
    }

    @Test
    void anOlderOfferDoesNotHideARecentOneForTheSameCourier() {
        List<Long> candidates = CandidateSelection.select(
                List.of(at(1, 0.4)),
                List.of(offeredAgo(1, Duration.ofMinutes(20)), offeredAgo(1, Duration.ofMinutes(1))),
                NOW);

        assertThat(candidates).isEmpty();
    }

    @Test
    void listsEachCourierOnce() {
        List<Long> candidates = CandidateSelection.select(List.of(at(1, 0.4), at(1, 0.4), at(2, 1.1)), List.of(), NOW);

        assertThat(candidates).containsExactly(1L, 2L);
    }
}
