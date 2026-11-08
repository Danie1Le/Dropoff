package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * A courier went OFFLINE after accepting an order but before picking it up (docs/PLAN.md, "Shared contracts"; tickets
 * #11, #85). Published by dispatch, which puts the order back to WAITING; the order module clears the courier from the
 * order. {@code offerId} is the accepted offer being undone, so the order listener can ignore a stale event. Delivered
 * to listeners after the change commits.
 */
public record CourierUnassigned(UUID eventId, long orderId, long courierId, long offerId, Instant occurredAt) {}
