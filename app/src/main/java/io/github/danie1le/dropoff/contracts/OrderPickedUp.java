package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * The assigned courier picked the order up from the merchant (docs/PLAN.md, "Shared contracts"). Published by the order
 * module; dispatch moves its copy of the order to PICKED_UP. Delivered to listeners after the change commits.
 */
public record OrderPickedUp(UUID eventId, long orderId, long courierId, Instant occurredAt) {}
