package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * A merchant was created or changed (docs/PLAN.md, "Shared contracts"; ADR-0007). Published by merchant on every create
 * and update with the merchant's full current state, to the compacted "merchants" topic keyed by merchantId, so
 * the latest record per merchant is its current state. The order module keeps its {@code merchant_copies} from it.
 */
public record MerchantChanged(
        UUID eventId,
        long merchantId,
        String name,
        String address,
        boolean open,
        double lat,
        double lng,
        Instant occurredAt) {}
