package io.github.danie1le.dropoff;

import org.junit.jupiter.api.Test;

/** Applies the shared rule from service-common's test fixtures to this service. */
class PageParamsValidationTest {

    @Test
    void everyControllerPageParamsParameterIsValidated() {
        PageParamsRule.check("io.github.danie1le.dropoff", "OrderListController.forMerchant");
    }
}
