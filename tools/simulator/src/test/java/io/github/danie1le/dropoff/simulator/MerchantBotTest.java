package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MerchantBotTest {

    private static final long MERCHANT = 3;
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");

    private final FakeApi api = new FakeApi();

    private static Instant at(int seconds) {
        return T0.plus(Duration.ofSeconds(seconds));
    }

    /** A random whose every delay is the shortest (5 s): nextInt always returns 0. */
    private static Random shortestDelays() {
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
    }

    @Test
    void pollsForPlacedOrdersEveryThreeSeconds() {
        MerchantBot bot = new MerchantBot(MERCHANT, api, new Random(42), new Stats());

        for (int s = 0; s <= 7; s++) {
            bot.tick(at(s));
        }

        // Polls at 0, 3 and 6 s.
        assertThat(api.callsStartingWith("placed")).hasSize(3);
    }

    @Test
    void stepsAnOrderThroughAcceptPrepareAndReadyWithADelayBeforeEachStep() {
        api.placedOrders.put(MERCHANT, new ArrayDeque<>(List.of(List.of(50L), List.of(50L))));
        MerchantBot bot = new MerchantBot(MERCHANT, api, shortestDelays(), new Stats());

        for (int s = 0; s <= 20; s++) {
            bot.tick(at(s));
            if (s == 4) {
                assertThat(api.callsStartingWith("ACCEPT")).isEmpty();
            }
        }

        // Seen at 0 s, then each step 5 s after the previous one.
        assertThat(api.calls)
                .containsSubsequence("placed 3", "ACCEPT 3 50", "START_PREPARING 3 50", "READY 3 50")
                .containsOnlyOnce("ACCEPT 3 50", "START_PREPARING 3 50", "READY 3 50");
        assertThat(api.calls.indexOf("ACCEPT 3 50")).isGreaterThan(api.calls.indexOf("placed 3"));
    }

    @Test
    void delaysStayBetweenFiveAndTwentySeconds() {
        Random random = new Random(42);

        for (int i = 0; i < 1_000; i++) {
            assertThat(MerchantBot.stepDelay(random)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(20));
        }
    }

    @Test
    void dropsAnOrderWhenAStepFailsSuchAsWhenItWasCancelled() {
        api.placedOrders.put(MERCHANT, new ArrayDeque<>(List.of(List.of(50L))));
        api.failOn = "ACCEPT";
        api.failure = new ApiException(409, "ORDER_STATUS_CONFLICT");
        MerchantBot bot = new MerchantBot(MERCHANT, api, shortestDelays(), new Stats());

        for (int s = 0; s <= 30; s++) {
            bot.tick(at(s));
        }

        assertThat(api.callsStartingWith("START_PREPARING")).isEmpty();
        assertThat(api.callsStartingWith("ACCEPT")).hasSize(1);
    }
}
