package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.danie1le.dropoff.web.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The courier status rules a courier may trigger itself (ticket #42, point 10; ticket #85). */
class CourierStatusTest {

    @Test
    void goesAvailableFromOfflineWithAKnownLocation() {
        assertThat(CourierStatus.OFFLINE.change(CourierStatus.AVAILABLE, true, false))
                .isEqualTo(CourierStatus.AVAILABLE);
    }

    @Test
    void refusesAvailableWithoutALocation() {
        assertThatThrownBy(() -> CourierStatus.OFFLINE.change(CourierStatus.AVAILABLE, false, false))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("COURIER_LOCATION_UNKNOWN");
                    assertThat(ex.category()).isEqualTo(DomainException.Category.CONFLICT);
                });
    }

    @Test
    void refusesAvailableWhileBusy() {
        assertThatThrownBy(() -> CourierStatus.BUSY.change(CourierStatus.AVAILABLE, true, false))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("COURIER_BUSY");
                    assertThat(ex.category()).isEqualTo(DomainException.Category.CONFLICT);
                });
    }

    @Test
    void repeatingAvailableIsANoOp() {
        assertThat(CourierStatus.AVAILABLE.change(CourierStatus.AVAILABLE, true, false))
                .isEqualTo(CourierStatus.AVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(CourierStatus.class)
    void goesOfflineFromAnyStatusWithoutAnActiveDelivery(CourierStatus from) {
        assertThat(from.change(CourierStatus.OFFLINE, false, false)).isEqualTo(CourierStatus.OFFLINE);
    }

    @Test
    void refusesOfflineWhileCarryingAPickedUpOrder() {
        assertThatThrownBy(() -> CourierStatus.BUSY.change(CourierStatus.OFFLINE, true, true))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("COURIER_HAS_ACTIVE_DELIVERY");
                    assertThat(ex.category()).isEqualTo(DomainException.Category.CONFLICT);
                });
    }

    @ParameterizedTest
    @EnumSource(CourierStatus.class)
    void neverLetsACourierSetBusyItself(CourierStatus from) {
        assertThatThrownBy(() -> from.change(CourierStatus.BUSY, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
