package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * A courier accepted the offer for an order (docs/PLAN.md, "Shared contracts"; ticket #11). Published by dispatch;
 * the order module records the courier on the order. Delivered to listeners after the accept commits.
 */
public record CourierAssigned(UUID eventId, long orderId, long courierId, long offerId, Instant occurredAt) {}
