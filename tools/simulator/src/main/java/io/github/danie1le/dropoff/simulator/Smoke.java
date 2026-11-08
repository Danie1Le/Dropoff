package io.github.danie1le.dropoff.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code smoke} command (M5-4): places a fixed number of orders, drives them with the merchant and courier bots
 * across the running services, and passes only if every order is DELIVERED before the timeout. {@code
 * ops/smoke/full-flow.sh} wraps it.
 *
 * <p>Merchant copies reach order-service through Kafka a moment after the merchant is created (M5-3), so right after a
 * seed an order can still be rejected with {@code MERCHANT_UNKNOWN} or {@code MENU_ITEM_UNKNOWN}. The smoke test
 * retries such an order every 2 s until the copies catch up. Couriers always accept, so no order waits out an ignored
 * offer: the test checks the whole flow, not the bots' moods.
 */
final class Smoke {

    static final int DEFAULT_ORDERS = 5;
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    /** Fast enough that a 17 km trip takes under two minutes. */
    static final double DEFAULT_SPEED_KMH = 600;

    static final Duration RETRY = Duration.ofSeconds(2);

    /** What order-service answers while its merchant copies still lack the merchant or the item (M5-3). */
    private static final Set<String> NOT_IN_COPIES_YET = Set.of("MERCHANT_UNKNOWN", "MENU_ITEM_UNKNOWN");

    private static final Set<String> FINAL = Set.of("DELIVERED", "CANCELLED");

    private static final Logger log = LoggerFactory.getLogger(Smoke.class);

    private final SimState state;
    private final int orders;
    private final DropoffApi api;
    private final Random random;
    private final List<Bot> bots;
    /** Order id to its last status read, in the order they were placed. */
    private final Map<Long, String> placed = new LinkedHashMap<>();

    private Instant nextOrderAt = Instant.MIN;
    private Instant nextCheckAt = Instant.MIN;

    Smoke(SimState state, int orders, double speedKmh, long seed, DropoffApi api) {
        this.state = state;
        this.orders = orders;
        this.api = api;
        this.random = new Random(seed);
        Stats stats = new Stats();
        List<Bot> all = new ArrayList<>();
        for (SimState.MerchantState merchant : state.merchants()) {
            all.add(new MerchantBot(merchant.id(), api, random, stats));
        }
        for (SimState.CourierState courier : state.couriers()) {
            all.add(new CourierBot(courier.id(), courier.home(), api, () -> OfferDecision.ACCEPT, stats, speedKmh));
        }
        this.bots = List.copyOf(all);
    }

    /** One pass: place the next order if one is due, check the statuses if due, then tick every bot. */
    void tick(Instant now) {
        if (placed.size() < orders && !now.isBefore(nextOrderAt)) {
            placeNext(now);
        }
        if (!placed.isEmpty() && !now.isBefore(nextCheckAt)) {
            nextCheckAt = now.plus(RETRY);
            checkStatuses();
        }
        for (Bot bot : bots) {
            bot.tick(now);
        }
    }

    /** Every order is placed and has reached DELIVERED or CANCELLED. */
    boolean finished() {
        return placed.size() == orders && placed.values().stream().allMatch(FINAL::contains);
    }

    /** Every order is placed and DELIVERED. */
    boolean passed() {
        return placed.size() == orders && placed.values().stream().allMatch("DELIVERED"::equals);
    }

    /** The orders placed so far and their last status read, for the report. */
    Map<Long, String> statuses() {
        return Map.copyOf(placed);
    }

    private void placeNext(Instant now) {
        long customerId = pick(state.customerIds());
        SimState.MerchantState merchant = pick(state.merchants());
        List<OrderLine> lines = CustomerBot.lines(merchant.menuItemIds(), random);
        try {
            long orderId = api.placeOrder(customerId, merchant.id(), lines);
            placed.put(orderId, "PLACED");
            log.info("Smoke order {} of {} placed: {}", placed.size(), orders, orderId);
        } catch (ApiException e) {
            nextOrderAt = now.plus(RETRY);
            if (NOT_IN_COPIES_YET.contains(e.code())) {
                log.info("Order rejected with {}: merchant copies not caught up yet, retrying", e.code());
            } else {
                log.warn("Order rejected: {}; retrying", e.getMessage());
            }
        } catch (RuntimeException e) {
            nextOrderAt = now.plus(RETRY);
            log.warn("Order failed: {}; retrying", e.getMessage());
        }
    }

    private void checkStatuses() {
        for (Map.Entry<Long, String> order : placed.entrySet()) {
            if (FINAL.contains(order.getValue())) {
                continue;
            }
            try {
                order.setValue(api.orderStatus(order.getKey()));
            } catch (RuntimeException e) {
                log.warn("Could not read order {}: {}", order.getKey(), e.getMessage());
            }
        }
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    /**
     * Ticks every 250 ms until every order is final or the timeout passes.
     *
     * @return whether every order was DELIVERED in time
     */
    boolean run(Clock clock, Duration timeout) {
        Instant start = clock.instant();
        Instant end = start.plus(timeout);
        log.info("Smoke test: {} orders, timeout {}", orders, timeout);
        while (!finished()) {
            Instant now = clock.instant();
            if (!now.isBefore(end)) {
                break;
            }
            tick(now);
            try {
                Thread.sleep(Simulation.TICK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Duration took = Duration.between(start, clock.instant());
        if (passed()) {
            log.info("Smoke test passed: {} orders DELIVERED in {} s", orders, took.toSeconds());
            return true;
        }
        log.error(
                "Smoke test failed after {} s: {} of {} orders placed, statuses {}",
                took.toSeconds(),
                placed.size(),
                orders,
                placed);
        return false;
    }
}
