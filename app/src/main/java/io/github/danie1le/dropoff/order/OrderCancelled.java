package io.github.danie1le.dropoff.order;

import java.time.Instant;
import java.util.UUID;

/**
 * An order was cancelled (docs/PLAN.md, "Shared contracts"). {@code actor} is who cancelled it: CUSTOMER, MERCHANT or
 * SYSTEM. Delivered to listeners after the change commits.
 */
public record OrderCancelled(UUID eventId, long orderId, long merchantId, Actor actor, Instant occurredAt) {}
