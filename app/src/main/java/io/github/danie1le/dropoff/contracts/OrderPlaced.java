package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/** An order was placed (docs/PLAN.md, "Shared contracts"). Delivered to listeners after the order commits. */
public record OrderPlaced(
        UUID eventId, long orderId, long customerId, long merchantId, long totalCents, Instant occurredAt) {}
