package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * A delivering courier's location (docs/PLAN.md, "Shared contracts"; tickets #12, #15). Published by dispatch, at most
 * once per courier every 5 s, one event for each ASSIGNED or PICKED_UP order the courier holds; the order module pushes
 * it to the order's live topic. {@code at} is when the location was saved. Sent after the save commits, straight to
 * the {@code courier-locations} topic with {@code KafkaTemplate}, not through the event registry: losing one is fine,
 * since the next arrives within seconds.
 */
public record CourierLocationUpdated(
        UUID eventId, long courierId, long orderId, double lat, double lng, Instant at, Instant occurredAt) {}
