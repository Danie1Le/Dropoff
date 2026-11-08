package io.github.danie1le.dropoff.simulator;

import java.time.Instant;

/**
 * One simulated actor. The run calls {@link #tick} on every bot in turn, on one thread, so with a fixed seed the bots
 * make the same choices in the same order. Each bot keeps its own due times; tests pass in any {@link Instant} as a fake
 * clock instead of sleeping.
 */
interface Bot {

    void tick(Instant now);
}
