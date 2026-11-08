package io.github.danie1le.dropoff.simulator;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Command-line arguments: {@code seed|run|smoke|watch <orderId> [--merchant-url URL] [--order-url URL] [--dispatch-url
 * URL] [--seed N] [--state FILE] [--orders-per-minute N] [--speed-kmh N] [--duration 90s|10m|1h] [--orders N]
 * [--timeout 5m]}. Without {@code --duration} (null), {@code run} goes on until stopped with Ctrl+C. {@code orderId} is
 * set only for {@code watch}, and null otherwise. {@code --orders} and {@code --timeout} are for {@code smoke}.
 */
record Options(
        Command command,
        ServiceUrls urls,
        long seed,
        Path stateFile,
        int ordersPerMinute,
        double speedKmh,
        @Nullable Duration duration,
        @Nullable Long orderId,
        int smokeOrders,
        Duration smokeTimeout) {

    enum Command {
        SEED,
        RUN,
        SMOKE,
        WATCH
    }

    static final String USAGE = "Usage: seed|run|smoke|watch <orderId> [--merchant-url http://localhost:8081]"
            + " [--order-url http://localhost:8082] [--dispatch-url http://localhost:8083] [--seed 42]"
            + " [--state .simulator/state.json] [--orders-per-minute 6] [--speed-kmh 30] [--duration 10m]"
            + " [--orders 5] [--timeout 5m]";

    static Options parse(String... args) {
        if (args.length == 0) {
            throw new IllegalArgumentException(USAGE);
        }
        Command command = switch (args[0]) {
            case "seed" -> Command.SEED;
            case "run" -> Command.RUN;
            case "smoke" -> Command.SMOKE;
            case "watch" -> Command.WATCH;
            default -> throw new IllegalArgumentException("Unknown command " + args[0] + ". " + USAGE);
        };
        URI merchantUrl = ServiceUrls.LOCAL.merchant();
        URI orderUrl = ServiceUrls.LOCAL.order();
        URI dispatchUrl = ServiceUrls.LOCAL.dispatch();
        long seed = 42;
        Path stateFile = Path.of(".simulator", "state.json");
        int ordersPerMinute = 6;
        @Nullable Double speedKmh = null;
        @Nullable Duration duration = null;
        @Nullable Long orderId = null;
        int smokeOrders = Smoke.DEFAULT_ORDERS;
        Duration smokeTimeout = Smoke.DEFAULT_TIMEOUT;
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
                case "--merchant-url" -> merchantUrl = URI.create(value);
                case "--order-url" -> orderUrl = URI.create(value);
                case "--dispatch-url" -> dispatchUrl = URI.create(value);
                case "--seed" -> seed = Long.parseLong(value);
                case "--state" -> stateFile = Path.of(value);
                case "--orders-per-minute" -> ordersPerMinute = Integer.parseInt(value);
                case "--speed-kmh" -> speedKmh = Double.parseDouble(value);
                case "--duration" -> duration = parseDuration(value);
                case "--orders" -> smokeOrders = Integer.parseInt(value);
                case "--timeout" -> smokeTimeout = parseDuration(value);
                default -> throw new IllegalArgumentException("Unknown option " + args[i] + ". " + USAGE);
            }
        }
        if (ordersPerMinute < 1) {
            throw new IllegalArgumentException("--orders-per-minute must be at least 1");
        }
        if (speedKmh != null && !(speedKmh > 0)) {
            throw new IllegalArgumentException("--speed-kmh must be above 0");
        }
        if (smokeOrders < 1) {
            throw new IllegalArgumentException("--orders must be at least 1");
        }
        if (smokeTimeout.isNegative() || smokeTimeout.isZero()) {
            throw new IllegalArgumentException("--timeout must be above 0");
        }
        // A smoke test must finish in minutes, so its couriers drive much faster unless told otherwise.
        double speed = speedKmh != null
                ? speedKmh
                : command == Command.SMOKE ? Smoke.DEFAULT_SPEED_KMH : CourierBot.DEFAULT_SPEED_KMH;
        return new Options(
                command,
                new ServiceUrls(merchantUrl, orderUrl, dispatchUrl),
                seed,
                stateFile,
                ordersPerMinute,
                speed,
                duration,
                orderId,
                smokeOrders,
                smokeTimeout);
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
