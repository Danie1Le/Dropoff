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
        assertThat(options.baseUrl()).isEqualTo(URI.create("http://localhost:8080"));
        assertThat(options.seed()).isEqualTo(42);
        assertThat(options.stateFile()).isEqualTo(Path.of(".simulator", "state.json"));
        assertThat(options.ordersPerMinute()).isEqualTo(6);
        assertThat(options.speedKmh()).isEqualTo(30);
        assertThat(options.duration()).isNull();
    }

    @Test
    void readsEveryOption() {
        Options options = Options.parse(
                "seed",
                "--base-url",
                "http://localhost:9090",
                "--seed",
                "7",
                "--state",
                "tmp/s.json",
                "--orders-per-minute",
                "12",
                "--speed-kmh",
                "90",
                "--duration",
                "5m");

        assertThat(options.command()).isEqualTo(Options.Command.SEED);
        assertThat(options.baseUrl()).isEqualTo(URI.create("http://localhost:9090"));
        assertThat(options.seed()).isEqualTo(7);
        assertThat(options.stateFile()).isEqualTo(Path.of("tmp/s.json"));
        assertThat(options.ordersPerMinute()).isEqualTo(12);
        assertThat(options.speedKmh()).isEqualTo(90);
        assertThat(options.duration()).isEqualTo(Duration.ofMinutes(5));
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
    }
}
