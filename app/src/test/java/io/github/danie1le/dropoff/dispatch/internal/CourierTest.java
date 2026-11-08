package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Releasing a courier after a decline (ticket #11, point 8). */
class CourierTest {

    @Test
    void anOfflineCourierStaysOfflineWhenReleased() {
        Courier courier = new Courier("Carla", Instant.parse("2026-01-01T00:00:00Z"));

        courier.release(Instant.parse("2026-01-01T00:01:00Z"));

        assertThat(courier.status()).isEqualTo(CourierStatus.OFFLINE);
    }
}
