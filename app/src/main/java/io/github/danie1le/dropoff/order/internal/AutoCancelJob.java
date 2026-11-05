package io.github.danie1le.dropoff.order.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels orders the merchant has not accepted in time (M2-7, decisions #13, #14). Every 30 s it cancels, as SYSTEM,
 * PLACED orders older than {@code dropoff.orders.auto-cancel-after}: one order per transaction, at most
 * {@value #MAX_PER_RUN} per run, safe on several instances because each row is taken with {@code SKIP LOCKED}.
 *
 * <p>Public only because {@code AutoCancelJobIT} lives in the {@code order} package and drives {@link #run} directly;
 * Modulith still treats {@code internal} as closed to other modules.
 */
@Component
public class AutoCancelJob {

    static final int MAX_PER_RUN = 50;

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
        // Each call is its own transaction, so one failing order never rolls back the others.
        int cancelled = 0;
        while (cancelled < MAX_PER_RUN && commands.cancelOldestPlacedBefore(cutoff)) {
            cancelled++;
        }
    }
}
