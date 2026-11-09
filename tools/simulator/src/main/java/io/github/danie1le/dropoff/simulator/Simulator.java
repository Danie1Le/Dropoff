package io.github.danie1le.dropoff.simulator;

import java.nio.file.Files;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: {@code ./gradlew :tools:simulator:run --args="seed"}, then {@code --args="run"}, with the services
 * running on {@code --merchant-url}, {@code --order-url} and {@code --dispatch-url} (see {@link ServiceUrls} for the
 * defaults). {@code --args="watch 7"} prints order 7's live {@code STATUS} and {@code LOCATION} messages until it is
 * delivered or cancelled (M4-3), and exits at once if it already is. {@code --args="smoke --orders 5 --timeout 5m"}
 * seeds if there is no state file, then exits 0 only if every order it places is DELIVERED in time (M5-4). The
 * simulator is a separate program: nothing runs unless it is started by hand, and the services' tests never start it.
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
        DropoffApi api = new HttpDropoffApi(options.urls());
        switch (options.command()) {
            case SEED -> seed(options, api);
            case RUN -> {
                SimState state = StateFile.read(options.stateFile());
                Simulation.of(state, options.ordersPerMinute(), options.speedKmh(), options.seed(), api)
                        .run(Clock.systemUTC(), options.duration());
            }
            case SMOKE -> {
                boolean reused = Files.exists(options.stateFile());
                SimState state = reused ? StateFile.read(options.stateFile()) : seed(options, api);
                boolean passed = new Smoke(state, options.smokeOrders(), options.speedKmh(), options.seed(), api)
                        .run(Clock.systemUTC(), options.smokeTimeout(), reused);
                System.exit(passed ? 0 : 1);
            }
            case WATCH -> {
                long orderId = Objects.requireNonNull(options.orderId(), "watch needs an order id");
                OrderWatch watch =
                        WebSocketTransport.open(orderId, options.urls().order(), api, System.out::println);
                try {
                    watch.done().join();
                } catch (CompletionException e) {
                    System.exit(1);
                }
            }
        }
    }

    private static SimState seed(Options options, DropoffApi api) {
        SimState state = new Seeder(api).seed(options.seed());
        StateFile.write(options.stateFile(), state);
        log.info("Seeded with seed {}; ids written to {}", options.seed(), options.stateFile());
        return state;
    }
}
