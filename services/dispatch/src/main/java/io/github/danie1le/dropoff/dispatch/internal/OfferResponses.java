package io.github.danie1le.dropoff.dispatch.internal;

import java.time.Instant;

/** Response bodies of the offer endpoints (ticket #11, docs/PLAN.md "Shared contracts"). */
final class OfferResponses {

    private OfferResponses() {}

    /** The courier's open offer, with what they need to decide: where to pick up and where to deliver. */
    record CurrentOffer(
            long offerId,
            long orderId,
            String merchantName,
            String merchantAddress,
            double pickupLat,
            double pickupLng,
            String deliveryAddress,
            double deliveryLat,
            double deliveryLng,
            Instant expiresAt) {}

    /** The offer after an accept or a decline, also when the command was a repeat. */
    record OfferResponse(long offerId, long orderId, OfferStatus status, Instant respondedAt) {}
}
