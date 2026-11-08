package io.github.danie1le.dropoff.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A merchant (ticket #33). It polls {@code GET /merchants/{id}/orders?status=PLACED} every 3 s, then steps each new
 * order through accept, start-preparing and ready, waiting a random 5–20 s before each step.
 */
final class MerchantBot implements Bot {

    static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    static final Duration MIN_STEP_DELAY = Duration.ofSeconds(5);
    static final Duration MAX_STEP_DELAY = Duration.ofSeconds(20);

    private static final Logger log = LoggerFactory.getLogger(MerchantBot.class);

    private final long merchantId;
    private final DropoffApi api;
    private final Random random;
    private final Stats stats;

    /** Orders in progress, by id, in the order they were first seen so a run is repeatable. */
    private final Map<Long, PendingStep> pending = new LinkedHashMap<>();

    private static final int CONFLICT = 409;

    private Instant nextPollAt = Instant.MIN;

    private record PendingStep(MerchantStep step, Instant dueAt) {}

    MerchantBot(long merchantId, DropoffApi api, Random random, Stats stats) {
        this.merchantId = merchantId;
        this.api = api;
        this.random = random;
        this.stats = stats;
    }

    /** A random delay from 5 s to 20 s, both inclusive, to the millisecond. */
    static Duration stepDelay(Random random) {
        long spreadMillis = MAX_STEP_DELAY.minus(MIN_STEP_DELAY).toMillis();
        return MIN_STEP_DELAY.plusMillis(random.nextInt((int) spreadMillis + 1));
    }

    @Override
    public void tick(Instant now) {
        if (!now.isBefore(nextPollAt)) {
            nextPollAt = now.plus(POLL_INTERVAL);
            poll(now);
        }
        runDueSteps(now);
    }

    private void poll(Instant now) {
        List<Long> placed;
        try {
            placed = api.placedOrders(merchantId);
        } catch (RuntimeException e) {
            stats.errors++;
            log.warn("Merchant {} could not list its orders: {}", merchantId, e.getMessage());
            return;
        }
        for (long orderId : placed) {
            pending.computeIfAbsent(orderId, id -> new PendingStep(MerchantStep.ACCEPT, now.plus(stepDelay(random))));
        }
    }

    private void runDueSteps(Instant now) {
        Iterator<Map.Entry<Long, PendingStep>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Long, PendingStep> entry = entries.next();
            PendingStep due = entry.getValue();
            if (now.isBefore(due.dueAt())) {
                continue;
            }
            long orderId = entry.getKey();
            try {
                api.merchantStep(merchantId, orderId, due.step());
            } catch (ApiException e) {
                stats.errors++;
                if (e.status() == CONFLICT) {
                    // The order was cancelled meanwhile (by the customer or the auto-cancel job); it stays 409.
                    log.warn("Merchant {} dropped order {} at {}: {}", merchantId, orderId, due.step(), e.getMessage());
                    entries.remove();
                } else {
                    retryLater(entry, now, e);
                }
                continue;
            } catch (RuntimeException e) {
                // A lost connection or a timeout: dropped, the order would stay ACCEPTED or PREPARING forever.
                stats.errors++;
                retryLater(entry, now, e);
                continue;
            }
            MerchantStep next = due.step().next();
            if (next == null) {
                stats.ordersReady++;
                entries.remove();
            } else {
                entry.setValue(new PendingStep(next, now.plus(stepDelay(random))));
            }
        }
    }

    private void retryLater(Map.Entry<Long, PendingStep> entry, Instant now, RuntimeException e) {
        PendingStep due = entry.getValue();
        log.warn(
                "Merchant {} will retry order {} at {} in {} s: {}",
                merchantId,
                entry.getKey(),
                due.step(),
                POLL_INTERVAL.toSeconds(),
                e.getMessage());
        entry.setValue(new PendingStep(due.step(), now.plus(POLL_INTERVAL)));
    }
}
