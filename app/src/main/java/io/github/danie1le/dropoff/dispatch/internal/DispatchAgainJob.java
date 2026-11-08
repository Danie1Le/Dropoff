package io.github.danie1le.dropoff.dispatch.internal;

import java.util.LinkedHashSet;
import java.util.SequencedSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispatches WAITING orders that have no open offer (M3-2, decisions #13, #14): ones no courier was in range for, or
 * whose dispatch failed. Every 10 s it takes them oldest first: one order per transaction, at most {@value #MAX_PER_RUN}
 * per run, safe on several instances because each row is taken with {@code SKIP LOCKED}. An order that fails or finds
 * no courier is skipped for the rest of the run; the next run tries it again.
 */
@Component
class DispatchAgainJob {

    /** The most orders one run tries to dispatch, failed attempts included. */
    static final int MAX_PER_RUN = 50;

    private static final Logger log = LoggerFactory.getLogger(DispatchAgainJob.class);

    private final DispatchService dispatch;

    DispatchAgainJob(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    /** One run of the job; tests drive it without the scheduler. */
    @Scheduled(fixedDelayString = "${dropoff.dispatch.dispatch-again-interval:10s}")
    void run() {
        // Each call is its own transaction, so a failed dispatch rolls back only that order. Its id stays in `tried`,
        // so the next call steps past it instead of picking the same oldest row again.
        SequencedSet<Long> tried = new LinkedHashSet<>();
        for (int attempt = 0; attempt < MAX_PER_RUN; attempt++) {
            int before = tried.size();
            try {
                if (!dispatch.dispatchNextWaiting(tried)) {
                    return;
                }
            } catch (RuntimeException e) {
                if (tried.size() == before) {
                    log.warn("Dispatch-again run stopped: could not pick an order", e);
                    return;
                }
                log.warn("Dispatch of order {} failed; skipping it for this run", tried.getLast(), e);
            }
        }
    }
}
