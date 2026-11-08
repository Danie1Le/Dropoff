package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OptionsTest {

    @Test
    void usesTheDefaultsWhenOnlyTheCommandIsGiven() {
        Options options = Options.parse("run");

        assertThat(options.command()).isEqualTo(Options.Command.RUN);
        assertThat(options.urls())
                .isEqualTo(new ServiceUrls(
                        URI.create("http://localhost:8080"),
                        URI.create("http://localhost:8080"),
                        URI.create("http://localhost:8083")));
        assertThat(options.seed()).isEqualTo(42);
        assertThat(options.stateFile()).isEqualTo(Path.of(".simulator", "state.json"));
        assertThat(options.ordersPerMinute()).isEqualTo(6);
        assertThat(options.speedKmh()).isEqualTo(30);
        assertThat(options.duration()).isNull();
        assertThat(options.smokeOrders()).isEqualTo(5);
        assertThat(options.smokeTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void readsEveryOption() {
        Options options = Options.parse(
                "seed",
                "--merchant-url",
                "http://localhost:8081",
                "--order-url",
                "http://localhost:8082",
                "--dispatch-url",
                "http://localhost:9093",
                "--seed",
                "7",
                "--state",
                "tmp/s.json",
                "--orders-per-minute",
                "12",
                "--speed-kmh",
                "90",
                "--duration",
                "5m",
                "--orders",
                "3",
                "--timeout",
                "90s");

        assertThat(options.command()).isEqualTo(Options.Command.SEED);
        assertThat(options.urls())
                .isEqualTo(new ServiceUrls(
                        URI.create("http://localhost:8081"),
                        URI.create("http://localhost:8082"),
                        URI.create("http://localhost:9093")));
        assertThat(options.seed()).isEqualTo(7);
        assertThat(options.stateFile()).isEqualTo(Path.of("tmp/s.json"));
        assertThat(options.ordersPerMinute()).isEqualTo(12);
        assertThat(options.speedKmh()).isEqualTo(90);
        assertThat(options.duration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(options.smokeOrders()).isEqualTo(3);
        assertThat(options.smokeTimeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void smokeDrivesFastUnlessASpeedIsGiven() {
        Options smoke = Options.parse("smoke");

        assertThat(smoke.command()).isEqualTo(Options.Command.SMOKE);
        assertThat(smoke.speedKmh()).isEqualTo(Smoke.DEFAULT_SPEED_KMH);
        assertThat(Options.parse("smoke", "--speed-kmh", "90").speedKmh()).isEqualTo(90);
    }

    @Test
    void readsDurationsInSecondsMinutesAndHours() {
        assertThat(Options.parse("run", "--duration", "90s").duration()).isEqualTo(Duration.ofSeconds(90));
        assertThat(Options.parse("run", "--duration", "1h").duration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsAnUnknownCommandOrOption() {
        assertThatThrownBy(() -> Options.parse()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("fly")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("run", "--speed", "3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("run", "--seed")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("run", "--orders-per-minute", "0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("run", "--speed-kmh", "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("run", "--base-url", "http://x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("smoke", "--orders", "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("smoke", "--timeout", "0s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheOrderIdForWatch() {
        Options options = Options.parse("watch", "7", "--order-url", "http://localhost:9090");

        assertThat(options.command()).isEqualTo(Options.Command.WATCH);
        assertThat(options.orderId()).isEqualTo(7L);
        assertThat(options.urls().order()).isEqualTo(URI.create("http://localhost:9090"));
        assertThat(Options.parse("run").orderId()).isNull();
    }

    @Test
    void rejectsWatchWithoutAPositiveOrderId() {
        assertThatThrownBy(() -> Options.parse("watch")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("watch", "--order-url", "http://x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("watch", "0")).isInstanceOf(IllegalArgumentException.class);
    }
}
