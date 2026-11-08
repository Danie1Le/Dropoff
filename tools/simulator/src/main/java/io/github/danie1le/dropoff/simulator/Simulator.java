package io.github.danie1le.dropoff.simulator;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: {@code ./gradlew :tools:simulator:run --args="seed"}, then {@code --args="run"}, with the app running
 * on {@code --base-url} (default {@code http://localhost:8080}). {@code --args="watch 7"} prints order 7's live
 * {@code STATUS} and {@code LOCATION} messages until it is delivered or cancelled (M4-3), and exits at once if it already is. The simulator is a separate program: nothing runs
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
            case WATCH -> {
                long orderId = Objects.requireNonNull(options.orderId(), "watch needs an order id");
                OrderWatch watch = WebSocketTransport.open(orderId, options.baseUrl(), api, System.out::println);
                try {
                    watch.done().join();
                } catch (CompletionException e) {
                    System.exit(1);
                }
            }
        }
    }
}
