package io.github.danie1le.dropoff.simulator;

import org.jspecify.annotations.Nullable;

/** The merchant commands, in the order a merchant bot steps an order through them. */
enum MerchantStep {
    ACCEPT("accept"),
    START_PREPARING("start-preparing"),
    READY("ready");

    private final String path;

    MerchantStep(String path) {
        this.path = path;
    }

    /** The last segment of {@code POST /merchants/{merchantId}/orders/{orderId}/<path>}. */
    String path() {
        return path;
    }

    /** The step after this one, or null after READY. */
    @Nullable
    MerchantStep next() {
        return switch (this) {
            case ACCEPT -> START_PREPARING;
            case START_PREPARING -> READY;
            case READY -> null;
        };
    }
}
