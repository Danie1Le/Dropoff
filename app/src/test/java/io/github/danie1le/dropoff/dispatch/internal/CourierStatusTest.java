package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.danie1le.dropoff.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The courier status rules a courier may trigger itself (ticket #42, point 10). */
class CourierStatusTest {

    @Test
    void goesAvailableFromOfflineWithAKnownLocation() {
        assertThat(CourierStatus.OFFLINE.change(CourierStatus.AVAILABLE, true)).isEqualTo(CourierStatus.AVAILABLE);
    }

    @Test
    void refusesAvailableWithoutALocation() {
        assertThatThrownBy(() -> CourierStatus.OFFLINE.change(CourierStatus.AVAILABLE, false))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("COURIER_LOCATION_UNKNOWN");
                    assertThat(ex.category()).isEqualTo(DomainException.Category.CONFLICT);
                });
    }

    @Test
    void refusesAvailableWhileBusy() {
        assertThatThrownBy(() -> CourierStatus.BUSY.change(CourierStatus.AVAILABLE, true))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("COURIER_BUSY");
                    assertThat(ex.category()).isEqualTo(DomainException.Category.CONFLICT);
                });
    }

    @Test
    void repeatingAvailableIsANoOp() {
        assertThat(CourierStatus.AVAILABLE.change(CourierStatus.AVAILABLE, true))
                .isEqualTo(CourierStatus.AVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(CourierStatus.class)
    void goesOfflineFromAnyStatus(CourierStatus from) {
        assertThat(from.change(CourierStatus.OFFLINE, false)).isEqualTo(CourierStatus.OFFLINE);
    }

    @ParameterizedTest
    @EnumSource(CourierStatus.class)
    void neverLetsACourierSetBusyItself(CourierStatus from) {
        assertThatThrownBy(() -> from.change(CourierStatus.BUSY, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
