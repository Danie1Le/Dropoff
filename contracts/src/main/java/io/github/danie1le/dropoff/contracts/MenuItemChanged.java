package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * A menu item was created or changed (docs/PLAN.md, "Shared contracts"; ADR-0007). Published by merchant on every create
 * and update with the item's full current state, to the compacted {@code menu-items} topic keyed by menuItemId, so the
 * latest record per item is its current state. The order module validates and prices orders from its copy.
 */
public record MenuItemChanged(
        UUID eventId,
        long menuItemId,
        long merchantId,
        String name,
        long priceCents,
        boolean available,
        Instant occurredAt) {}
