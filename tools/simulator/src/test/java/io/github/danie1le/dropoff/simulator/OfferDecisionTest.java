package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OfferDecisionTest {

    @Test
    void splitsTheRollEightyFifteenFive() {
        assertThat(OfferDecision.forRoll(0.0)).isEqualTo(OfferDecision.ACCEPT);
        assertThat(OfferDecision.forRoll(0.7999)).isEqualTo(OfferDecision.ACCEPT);
        assertThat(OfferDecision.forRoll(0.80)).isEqualTo(OfferDecision.DECLINE);
        assertThat(OfferDecision.forRoll(0.9499)).isEqualTo(OfferDecision.DECLINE);
        assertThat(OfferDecision.forRoll(0.95)).isEqualTo(OfferDecision.IGNORE);
        assertThat(OfferDecision.forRoll(0.9999)).isEqualTo(OfferDecision.IGNORE);
    }

    @Test
    void aSeededRandomGivesRoughlyTheSpecifiedShares() {
        Random random = new Random(42);
        Map<OfferDecision, Integer> counts = new EnumMap<>(OfferDecision.class);

        for (int i = 0; i < 10_000; i++) {
            counts.merge(OfferDecision.roll(random), 1, Integer::sum);
        }

        assertThat(counts.get(OfferDecision.ACCEPT)).isBetween(7_800, 8_200);
        assertThat(counts.get(OfferDecision.DECLINE)).isBetween(1_350, 1_650);
        assertThat(counts.get(OfferDecision.IGNORE)).isBetween(400, 600);
    }
}
