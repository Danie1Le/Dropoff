package io.github.danie1le.dropoff.simulator;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Command-line arguments: {@code seed|run [--base-url URL] [--seed N] [--state FILE] [--orders-per-minute N]
 * [--speed-kmh N] [--duration 90s|10m|1h]}. Without {@code --duration} (null), {@code run} goes on until stopped with Ctrl+C.
 */
record Options(
        Command command,
        URI baseUrl,
        long seed,
        Path stateFile,
        int ordersPerMinute,
        double speedKmh,
        @Nullable Duration duration) {

    enum Command {
        SEED,
        RUN
    }

    static final String USAGE = "Usage: seed|run [--base-url http://localhost:8080] [--seed 42]"
            + " [--state .simulator/state.json] [--orders-per-minute 6] [--speed-kmh 30] [--duration 10m]";

    static Options parse(String... args) {
        if (args.length == 0) {
            throw new IllegalArgumentException(USAGE);
        }
        Command command = switch (args[0]) {
            case "seed" -> Command.SEED;
            case "run" -> Command.RUN;
            default -> throw new IllegalArgumentException("Unknown command " + args[0] + ". " + USAGE);
        };
        URI baseUrl = URI.create("http://localhost:8080");
        long seed = 42;
        Path stateFile = Path.of(".simulator", "state.json");
        int ordersPerMinute = 6;
        double speedKmh = CourierBot.DEFAULT_SPEED_KMH;
        @Nullable Duration duration = null;
        for (int i = 1; i < args.length; i += 2) {
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
        return new Options(command, baseUrl, seed, stateFile, ordersPerMinute, speedKmh, duration);
    }

    private static Duration parseDuration(String value) {
        return Duration.parse("PT" + value.toUpperCase(Locale.ROOT));
    }
}
