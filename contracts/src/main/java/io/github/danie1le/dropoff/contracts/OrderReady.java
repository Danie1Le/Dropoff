package io.github.danie1le.dropoff.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * An order is ready for pickup (docs/PLAN.md, "Shared contracts"). Carries everything dispatch needs, so dispatch never
 * reads {@code orders}. Delivered to listeners after the change commits.
 */
public record OrderReady(
        UUID eventId,
        long orderId,
        long merchantId,
        String merchantName,
        String merchantAddress,
        double pickupLat,
        double pickupLng,
        String deliveryAddress,
        double deliveryLat,
        double deliveryLng,
        Instant readyAt,
        Instant occurredAt) {}
