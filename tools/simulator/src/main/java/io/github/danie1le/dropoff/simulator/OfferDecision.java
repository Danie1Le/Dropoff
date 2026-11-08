package io.github.danie1le.dropoff.simulator;

import java.util.Random;

/** How a courier bot answers an offer (ticket #33): accept 80%, decline 15%, ignore 5% to exercise the timeout. */
enum OfferDecision {
    ACCEPT,
    DECLINE,
    IGNORE;

    private static final double ACCEPT_BELOW = 0.80;
    private static final double DECLINE_BELOW = 0.95;

    /** The decision for a roll in {@code [0, 1)}. */
    static OfferDecision forRoll(double roll) {
        if (roll < ACCEPT_BELOW) {
            return ACCEPT;
        }
        return roll < DECLINE_BELOW ? DECLINE : IGNORE;
    }

    static OfferDecision roll(Random random) {
        return forRoll(random.nextDouble());
    }
}
