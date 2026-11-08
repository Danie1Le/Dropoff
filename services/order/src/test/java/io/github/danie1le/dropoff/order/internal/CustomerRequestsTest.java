package io.github.danie1le.dropoff.order.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.danie1le.dropoff.order.internal.CustomerRequests.UpdateCustomer;
import org.junit.jupiter.api.Test;

class CustomerRequestsTest {

    @Test
    void updateWithoutLocationFieldsLeavesTheLocationUnchanged() {
        assertThat(new UpdateCustomer("Alice B", null, null, null).location()).isNull();
    }

    @Test
    void updateWithAllLocationFieldsGivesTheNewLocation() {
        assertThat(new UpdateCustomer(null, "500 Lamar Blvd", 30.27, -97.75).location())
                .isEqualTo(new Location("500 Lamar Blvd", 30.27, -97.75));
    }

    @Test
    void missingLocationFieldsNamesEachAbsentFieldOfAPartialLocation() {
        assertThat(new UpdateCustomer(null, null, 30.0, null).missingLocationFields())
                .containsExactly("address", "lng");
        assertThat(new UpdateCustomer(null, null, null, null).missingLocationFields())
                .isEmpty();
    }

    @Test
    void updateWithAPartialLocationThrows() {
        UpdateCustomer request = new UpdateCustomer(null, "500 Lamar Blvd", null, -97.75);

        assertThatThrownBy(request::location).isInstanceOf(IllegalStateException.class);
    }
}
