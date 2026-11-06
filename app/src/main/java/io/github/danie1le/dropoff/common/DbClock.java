package io.github.danie1le.dropoff.common;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Timestamps that survive a database round trip unchanged. */
public final class DbClock {

    private DbClock() {}

    /**
     * The current instant, truncated to microseconds. Postgres keeps microseconds, so a value written now reads back
     * equal to the one already in memory (in events and responses).
     */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
