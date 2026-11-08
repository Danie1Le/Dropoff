package io.github.danie1le.dropoff.simulator;

/** Counters the run prints every 30 s. The simulation is single-threaded, so plain ints are enough. */
final class Stats {

    int ordersPlaced;
    int ordersReady;
    int offersAccepted;
    int offersDeclined;
    int offersIgnored;
    int pickedUp;
    int delivered;
    int errors;

    @Override
    public String toString() {
        return "placed=" + ordersPlaced + " ready=" + ordersReady + " offers(accepted=" + offersAccepted
                + " declined=" + offersDeclined + " ignored=" + offersIgnored + ") pickedUp=" + pickedUp
                + " delivered=" + delivered + " errors=" + errors;
    }
}
