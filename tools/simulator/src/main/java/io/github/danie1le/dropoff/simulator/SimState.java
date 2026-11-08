package io.github.danie1le.dropoff.simulator;

import java.util.List;

/** What {@code seed} created, saved to {@code .simulator/state.json} so {@code run} can drive it. */
record SimState(long seed, List<MerchantState> merchants, List<Long> customerIds, List<CourierState> couriers) {

    SimState {
        merchants = List.copyOf(merchants);
        customerIds = List.copyOf(customerIds);
        couriers = List.copyOf(couriers);
    }

    record MerchantState(long id, LatLng location, List<Long> menuItemIds) {
        MerchantState {
            menuItemIds = List.copyOf(menuItemIds);
        }
    }

    /** {@code home} is where the seed put the courier; a run starts from the courier's current server position. */
    record CourierState(long id, LatLng home) {}
}
