package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * The assigned courier delivered the order to the customer (docs/PLAN.md, "Shared contracts"). Published by the order
 * module; dispatch closes its copy of the order and frees the courier. Delivered to listeners after the change commits.
 */
public record OrderDelivered(UUID eventId, long orderId, long courierId, Instant occurredAt) {}
