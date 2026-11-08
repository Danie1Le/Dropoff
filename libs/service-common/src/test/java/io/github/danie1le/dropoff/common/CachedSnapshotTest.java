package io.github.danie1le.dropoff.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CachedSnapshotTest {

    private Instant now = Instant.parse("2026-11-08T12:00:00Z");
    private final AtomicInteger loads = new AtomicInteger();

    private Clock clock() {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
    }

    @Test
    void readsWithinTheMaxAgeShareOneLoad() {
        CachedSnapshot<Integer> snapshot =
                new CachedSnapshot<>(loads::incrementAndGet, Duration.ofSeconds(15), clock());

        assertThat(snapshot.get()).isEqualTo(1);
        now = now.plusSeconds(14);
        assertThat(snapshot.get()).isEqualTo(1);
        assertThat(loads).hasValue(1);
    }

    @Test
    void aReadAfterTheMaxAgeLoadsAgain() {
        CachedSnapshot<Integer> snapshot =
                new CachedSnapshot<>(loads::incrementAndGet, Duration.ofSeconds(15), clock());

        snapshot.get();
        now = now.plusSeconds(15);

        assertThat(snapshot.get()).isEqualTo(2);
    }

    @Test
    void aFailedLoadKeepsTheLastValue() {
        CachedSnapshot<Integer> snapshot = new CachedSnapshot<>(
                () -> {
                    if (loads.incrementAndGet() > 1) {
                        throw new IllegalStateException("database down");
                    }
                    return 7;
                },
                Duration.ofSeconds(15),
                clock());

        snapshot.get();
        now = now.plusSeconds(20);

        assertThat(snapshot.get()).isEqualTo(7);
    }

    @Test
    void aFailedFirstLoadReturnsNull() {
        CachedSnapshot<Integer> snapshot = new CachedSnapshot<>(
                () -> {
                    throw new IllegalStateException("database down");
                },
                Duration.ofSeconds(15),
                clock());

        assertThat(snapshot.get()).isNull();
    }
}
