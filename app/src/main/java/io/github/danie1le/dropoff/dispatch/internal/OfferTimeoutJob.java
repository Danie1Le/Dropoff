package io.github.danie1le.dropoff.dispatch.internal;

import java.util.LinkedHashSet;
import java.util.SequencedSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Times out offers nobody answered (M3-4, decisions #11, #14, ticket #85). Every 5 s it takes open offers past their
 * {@code expires_at}, oldest first: one offer per transaction, at most {@value #MAX_PER_RUN} per run, safe on several
 * instances because each row is taken with {@code SKIP LOCKED}. Each offer becomes TIMED_OUT, its courier OFFLINE, and
 * its order is dispatched again. An offer whose timeout fails is skipped for the rest of the run; the next run tries it
 * again.
 */
@Component
class OfferTimeoutJob {

    /** The most offers one run tries to time out, failed attempts included. */
    static final int MAX_PER_RUN = 50;

    private static final Logger log = LoggerFactory.getLogger(OfferTimeoutJob.class);

    private final OfferService offers;

    OfferTimeoutJob(OfferService offers) {
        this.offers = offers;
    }

    /** One run of the job; tests drive it without the scheduler. */
    @Scheduled(fixedDelayString = "${dropoff.dispatch.offer-timeout-interval:5s}")
    void run() {
        // Each call is its own transaction, so a failed timeout rolls back only that offer. Its id stays in `tried`, so
        // the next call steps past it instead of picking the same oldest row again.
        SequencedSet<Long> tried = new LinkedHashSet<>();
        for (int attempt = 0; attempt < MAX_PER_RUN; attempt++) {
            int before = tried.size();
            try {
                if (!offers.timeOutNextExpired(tried)) {
                    return;
                }
            } catch (RuntimeException e) {
                if (tried.size() == before) {
                    log.warn("Offer-timeout run stopped: could not pick an offer", e);
                    return;
                }
                log.warn("Timeout of offer {} failed; skipping it for this run", tried.getLast(), e);
            }
        }
    }
}
