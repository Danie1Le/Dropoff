package io.github.danie1le.dropoff;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Ids for tests that make rows or events across services. They sit above the int range, so JSON always reads one back
 * as a long, and count up from a random start, so tests never share an id, nor reuse one left on the shared broker.
 */
public final class TestIds {

    private static final long ID_BASE = 10_000_000_000L;

    private static final AtomicLong NEXT =
            new AtomicLong(ID_BASE + Math.floorMod(System.nanoTime(), 1_000_000_000L) * 1_000);

    private TestIds() {}

    /** An id larger than every id this method returned before. */
    public static long next() {
        return NEXT.incrementAndGet();
    }
}
