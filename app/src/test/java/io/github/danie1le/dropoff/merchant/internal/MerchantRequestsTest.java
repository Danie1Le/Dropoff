package io.github.danie1le.dropoff.merchant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.CreateMerchant;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.UpdateMerchant;
import org.junit.jupiter.api.Test;

class MerchantRequestsTest {

    @Test
    void updateWithoutLocationFieldsLeavesTheLocationUnchanged() {
        UpdateMerchant request = new UpdateMerchant("Taco Palace", null, null, null, null);

        assertThat(request.location()).isNull();
    }

    @Test
    void updateWithAllLocationFieldsGivesTheNewLocation() {
        UpdateMerchant request = new UpdateMerchant(null, null, "500 Lamar Blvd", 30.27, -97.75);

        assertThat(request.location()).isEqualTo(new Location("500 Lamar Blvd", 30.27, -97.75));
    }

    @Test
    void updateWithAPartialLocationThrows() {
        UpdateMerchant request = new UpdateMerchant(null, null, "500 Lamar Blvd", 30.27, null);

        assertThatThrownBy(request::location)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("location is incomplete; validate with @AddressChangedTogether first");
    }

    @Test
    void missingLocationFieldsNamesEachAbsentFieldOfAPartialLocation() {
        assertThat(new UpdateMerchant(null, null, null, 30.0, null).missingLocationFields())
                .containsExactly("address", "lng");
        assertThat(new UpdateMerchant(null, null, null, null, null).missingLocationFields())
                .isEmpty();
        assertThat(new UpdateMerchant(null, null, "500 Lamar Blvd", 30.27, -97.75).missingLocationFields())
                .isEmpty();
    }

    @Test
    void createWithoutALatitudeThrows() {
        CreateMerchant request = new CreateMerchant("Taco Shop", "100 Congress Ave", null, -97.7431);

        assertThatThrownBy(request::location)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("lat and lng are required; validate with @Valid first");
    }
}
