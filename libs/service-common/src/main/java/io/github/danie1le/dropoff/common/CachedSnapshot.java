package io.github.danie1le.dropoff.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A value loaded at most once per {@code maxAge}, for gauges (M6-1, ticket #34). Every gauge of a service reads one
 * snapshot, so one scrape runs one SQL query, and scrapes closer together than {@code maxAge} run none.
 *
 * <p>A failed load is logged and the last value kept, so a database blip never fails the scrape; the next read after
 * {@code maxAge} tries again. Before the first successful load the value is {@code null}, which a gauge reports as NaN.
 */
public final class CachedSnapshot<T> {

    private static final Logger log = LoggerFactory.getLogger(CachedSnapshot.class);

    private final Supplier<T> load;
    private final Duration maxAge;
    private final Clock clock;
    private @Nullable T value;
    private @Nullable Instant loadedAt;

    public CachedSnapshot(Supplier<T> load, Duration maxAge, Clock clock) {
        this.load = load;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** The cached value, loaded first if it is older than {@code maxAge}; {@code null} if no load has worked yet. */
    public synchronized @Nullable T get() {
        Instant now = clock.instant();
        if (loadedAt == null || !now.isBefore(loadedAt.plus(maxAge))) {
            loadedAt = now;
            try {
                value = load.get();
            } catch (RuntimeException e) {
                log.warn("Gauge snapshot failed to load; keeping the last value", e);
            }
        }
        return value;
    }
}
