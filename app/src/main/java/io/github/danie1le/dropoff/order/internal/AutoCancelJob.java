package io.github.danie1le.dropoff.order.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.SequencedSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels orders the merchant has not accepted in time (M2-7, decisions #13, #14). Every 30 s it cancels, as SYSTEM,
 * PLACED orders older than {@code dropoff.orders.auto-cancel-after}: one order per transaction, at most
 * {@value #MAX_PER_RUN} per run, safe on several instances because each row is taken with {@code SKIP LOCKED}. An order
 * whose cancel fails is logged and skipped for the rest of the run, so it never stalls the others.
 *
 * <p>Public only because {@code AutoCancelJobIT} lives in the {@code order} package and drives {@link #run} directly;
 * Modulith still treats {@code internal} as closed to other modules.
 */
@Component
public class AutoCancelJob {

    /** The most orders one run tries to cancel, failed attempts included. */
    static final int MAX_PER_RUN = 50;

    private static final Logger log = LoggerFactory.getLogger(AutoCancelJob.class);

    private final OrderCommandService commands;
    private final Clock clock;
    private final Duration autoCancelAfter;

    AutoCancelJob(
            OrderCommandService commands,
            Clock clock,
            @Value("${dropoff.orders.auto-cancel-after:10m}") Duration autoCancelAfter) {
        this.commands = commands;
        this.clock = clock;
        this.autoCancelAfter = autoCancelAfter;
    }

    /** One run of the job; public so tests can drive it without the scheduler. */
    @Scheduled(fixedDelayString = "${dropoff.orders.auto-cancel-interval:30s}")
    public void run() {
        Instant cutoff = clock.instant().minus(autoCancelAfter);
        // Each call is its own transaction, so a failed cancel rolls back only that order. Its id stays in `tried`, so
        // the next call steps past it instead of picking the same oldest row again; the next run retries it.
        SequencedSet<Long> tried = new LinkedHashSet<>();
        for (int attempt = 0; attempt < MAX_PER_RUN; attempt++) {
            int before = tried.size();
            try {
                if (!commands.cancelOldestPlacedBefore(cutoff, tried)) {
                    return;
                }
            } catch (RuntimeException e) {
                if (tried.size() == before) {
                    log.warn("Auto-cancel run stopped: could not pick an order", e);
                    return;
                }
                log.warn("Auto-cancel of order {} failed; skipping it for this run", tried.getLast(), e);
            }
        }
    }
}
