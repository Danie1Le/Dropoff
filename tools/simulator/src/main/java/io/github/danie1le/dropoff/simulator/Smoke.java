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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    /** How long the start-up state check retries before it calls the state file stale or the services unreachable. */
    static final Duration WARM_UP = Duration.ofSeconds(60);

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

    /** An order whose POST got no answer; the next try repeats it with the same key. */
    private @Nullable PendingOrder pending;

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
        PendingOrder order = pending != null ? pending : newOrder();
        pending = null;
        try {
            long orderId = api.placeOrder(order.customerId(), order.merchantId(), order.lines(), order.key());
            placed.put(orderId, "PLACED");
            log.info("Smoke order {} of {} placed: {}", placed.size(), orders, orderId);
        } catch (ApiException e) {
            // The server answered, so the order may not exist: the next try is a new order with a new key.
            nextOrderAt = now.plus(RETRY);
            if (NOT_IN_COPIES_YET.contains(e.code())) {
                log.info("Order rejected with {}: merchant copies not caught up yet, retrying", e.code());
            } else {
                log.warn("Order rejected: {}; retrying", e.getMessage());
            }
        } catch (RuntimeException e) {
            // No answer (a timeout, or a reset after the POST landed): the order may exist. Retry it with the same
            // body and key, so a replay returns that order instead of making a second one.
            pending = order;
            nextOrderAt = now.plus(RETRY);
            log.warn("Order failed: {}; retrying with the same Idempotency-Key", e.getMessage());
        }
    }

    private PendingOrder newOrder() {
        long customerId = pick(state.customerIds());
        SimState.MerchantState merchant = pick(state.merchants());
        return new PendingOrder(
                customerId,
                merchant.id(),
                CustomerBot.lines(merchant.menuItemIds(), random),
                UUID.randomUUID().toString());
    }

    /** One order and its Idempotency-Key, kept across retries of a transport failure. */
    private record PendingOrder(long customerId, long merchantId, List<OrderLine> lines, String key) {}

    /** Sleeps between warm-up probes; tests pass one that only moves their clock. */
    @FunctionalInterface
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Compares the state file with the running services, probing every {@link #RETRY} for up to {@link #WARM_UP}.
     *
     * <p>A 404 right after a seed does not mean the state file is stale: order-service's merchant copy (M5-3) can
     * lag the seed by a moment, and a service can still be starting. So any failed probe is retried, and only one
     * that still fails once the warm-up is over is reported.
     *
     * @return what is wrong (a stale state file after {@code docker compose down -v}, or a service that does not
     *     answer), or null once the first courier and merchant in the state file exist
     */
    @Nullable
    String stateProblem(Clock clock, Pause pause) {
        Instant end = clock.instant().plus(WARM_UP);
        while (true) {
            String problem = probeState();
            if (problem == null || !clock.instant().isBefore(end)) {
                return problem;
            }
            log.info("State check: {}; retrying", problem);
            try {
                pause.sleep(RETRY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return problem;
            }
        }
    }

    /** One probe of the first courier and merchant in the state file: what is wrong, or null if both exist. */
    private @Nullable String probeState() {
        long courierId = state.couriers().get(0).id();
        long merchantId = state.merchants().get(0).id();
        try {
            api.courier(courierId);
            api.placedOrders(merchantId);
            return null;
        } catch (ApiException e) {
            if (e.status() == 404) {
                return "state file is stale (courier " + courierId + " or merchant " + merchantId
                        + " not found): delete it or reseed";
            }
            return "services not ready: " + e.getMessage();
        } catch (RuntimeException e) {
            return "services not reachable: " + e.getMessage();
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
        String problem = stateProblem(clock, duration -> Thread.sleep(duration.toMillis()));
        if (problem != null) {
            log.error("Smoke test cannot start: {}", problem);
            return false;
        }
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
