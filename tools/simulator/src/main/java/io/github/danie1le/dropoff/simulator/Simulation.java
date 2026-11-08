package io.github.danie1le.dropoff.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code run} command: one customer bot, a merchant bot per merchant and a courier bot per courier, ticked in a
 * fixed order on one thread. All bots share one {@link Random} built from the seed, so a run makes the same choices in
 * the same order each time (as far as the server answers the same way).
 */
final class Simulation {

    static final Duration TICK = Duration.ofMillis(250);
    static final Duration REPORT_INTERVAL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(Simulation.class);

    private final List<Bot> bots;
    private final Stats stats;

    Simulation(List<Bot> bots, Stats stats) {
        this.bots = List.copyOf(bots);
        this.stats = stats;
    }

    static Simulation of(SimState state, int ordersPerMinute, double speedKmh, long seed, DropoffApi api) {
        Random random = new Random(seed);
        Stats stats = new Stats();
        List<Bot> bots = new ArrayList<>();
        bots.add(new CustomerBot(state, ordersPerMinute, api, random, stats));
        for (SimState.MerchantState merchant : state.merchants()) {
            bots.add(new MerchantBot(merchant.id(), api, random, stats));
        }
        for (SimState.CourierState courier : state.couriers()) {
            bots.add(new CourierBot(
                    courier.id(), courier.home(), api, () -> OfferDecision.roll(random), stats, speedKmh));
        }
        return new Simulation(bots, stats);
    }

    /** One pass over every bot. */
    void tick(Instant now) {
        for (Bot bot : bots) {
            bot.tick(now);
        }
    }

    /** Ticks every 250 ms until {@code duration} has passed (or forever when it is null), or the thread is stopped. */
    void run(Clock clock, @Nullable Duration duration) {
        Instant start = clock.instant();
        Instant end = duration == null ? Instant.MAX : start.plus(duration);
        Instant nextReport = start.plus(REPORT_INTERVAL);
        log.info("Running {} bots{}", bots.size(), duration == null ? " until Ctrl+C" : " for " + duration);
        while (true) {
            Instant now = clock.instant();
            if (!now.isBefore(end)) {
                break;
            }
            tick(now);
            if (!now.isBefore(nextReport)) {
                log.info("{}", stats);
                nextReport = now.plus(REPORT_INTERVAL);
            }
            try {
                Thread.sleep(TICK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Finished: {}", stats);
    }
}
