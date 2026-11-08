package io.github.danie1le.dropoff.simulator;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: {@code ./gradlew :tools:simulator:run --args="seed"}, then {@code --args="run"}, with the app running
 * on {@code --base-url} (default {@code http://localhost:8080}). The simulator is a separate program: nothing runs
 * unless it is started by hand, and the app's tests never start it.
 */
public final class Simulator {

    private static final Logger log = LoggerFactory.getLogger(Simulator.class);

    private Simulator() {}

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            System.exit(2);
            return;
        }
        DropoffApi api = new HttpDropoffApi(options.baseUrl());
        switch (options.command()) {
            case SEED -> {
                SimState state = new Seeder(api).seed(options.seed());
                StateFile.write(options.stateFile(), state);
                log.info("Seeded with seed {}; ids written to {}", options.seed(), options.stateFile());
            }
            case RUN -> {
                SimState state = StateFile.read(options.stateFile());
                Simulation.of(state, options.ordersPerMinute(), options.speedKmh(), options.seed(), api)
                        .run(Clock.systemUTC(), options.duration());
            }
        }
    }
}
