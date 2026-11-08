package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * A merchant was created or changed (docs/PLAN.md, "Shared contracts"; ADR-0007). Published by merchant on every create
 * and update with the merchant's full current state, to the compacted "merchants" topic keyed by merchantId. The order
 * module keeps its {@code merchant_copies} from it.
 *
 * <p>{@code version} starts at 1 on create and goes up by one on every change, in commit order. Records can arrive out
 * of order, so a consumer keeps the state with the highest version, not the last one it read.
 */
public record MerchantChanged(
        UUID eventId,
        long merchantId,
        String name,
        String address,
        boolean open,
        double lat,
        double lng,
        long version,
        Instant occurredAt) {}
