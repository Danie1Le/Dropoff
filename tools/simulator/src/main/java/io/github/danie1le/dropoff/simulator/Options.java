package io.github.danie1le.dropoff.simulator;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Command-line arguments: {@code seed|run|watch <orderId> [--base-url URL] [--seed N] [--state FILE]
 * [--orders-per-minute N] [--speed-kmh N] [--duration 90s|10m|1h]}. Without {@code --duration} (null), {@code run}
 * goes on until stopped with Ctrl+C. {@code orderId} is set only for {@code watch}, and null otherwise.
 */
record Options(
        Command command,
        URI baseUrl,
        long seed,
        Path stateFile,
        int ordersPerMinute,
        double speedKmh,
        @Nullable Duration duration,
        @Nullable Long orderId) {

    enum Command {
        SEED,
        RUN,
        WATCH
    }

    static final String USAGE = "Usage: seed|run|watch <orderId> [--base-url http://localhost:8080] [--seed 42]"
            + " [--state .simulator/state.json] [--orders-per-minute 6] [--speed-kmh 30] [--duration 10m]";

    static Options parse(String... args) {
        if (args.length == 0) {
            throw new IllegalArgumentException(USAGE);
        }
        Command command = switch (args[0]) {
            case "seed" -> Command.SEED;
            case "run" -> Command.RUN;
            case "watch" -> Command.WATCH;
            default -> throw new IllegalArgumentException("Unknown command " + args[0] + ". " + USAGE);
        };
        URI baseUrl = URI.create("http://localhost:8080");
        long seed = 42;
        Path stateFile = Path.of(".simulator", "state.json");
        int ordersPerMinute = 6;
        double speedKmh = CourierBot.DEFAULT_SPEED_KMH;
        @Nullable Duration duration = null;
        @Nullable Long orderId = null;
        int first = 1;
        if (command == Command.WATCH) {
            orderId = parseOrderId(args);
            first = 2;
        }
        for (int i = first; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + args[i] + ". " + USAGE);
            }
            String value = args[i + 1];
            switch (args[i]) {
                case "--base-url" -> baseUrl = URI.create(value);
                case "--seed" -> seed = Long.parseLong(value);
                case "--state" -> stateFile = Path.of(value);
                case "--orders-per-minute" -> ordersPerMinute = Integer.parseInt(value);
                case "--speed-kmh" -> speedKmh = Double.parseDouble(value);
                case "--duration" -> duration = parseDuration(value);
                default -> throw new IllegalArgumentException("Unknown option " + args[i] + ". " + USAGE);
            }
        }
        if (ordersPerMinute < 1) {
            throw new IllegalArgumentException("--orders-per-minute must be at least 1");
        }
        if (!(speedKmh > 0)) {
            throw new IllegalArgumentException("--speed-kmh must be above 0");
        }
        return new Options(command, baseUrl, seed, stateFile, ordersPerMinute, speedKmh, duration, orderId);
    }

    private static long parseOrderId(String... args) {
        if (args.length < 2 || args[1].startsWith("--")) {
            throw new IllegalArgumentException("watch needs an order id. " + USAGE);
        }
        long orderId = Long.parseLong(args[1]);
        if (orderId < 1) {
            throw new IllegalArgumentException("The order id must be at least 1");
        }
        return orderId;
    }

    private static Duration parseDuration(String value) {
        return Duration.parse("PT" + value.toUpperCase(Locale.ROOT));
    }
}
