package io.github.danie1le.dropoff.dispatch.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Response bodies of the offer endpoints (ticket #11, docs/PLAN.md "Shared contracts"). */
final class OfferResponses {

    private OfferResponses() {}

    /** The courier's open offer, with what they need to decide: where to pick up and where to deliver. */
    record CurrentOffer(
            @Schema(example = "31") long offerId,
            @Schema(example = "1042") long orderId,
            @Schema(example = "Pizza Palace") String merchantName,
            @Schema(example = "1 Dam Square, Amsterdam") String merchantAddress,
            @Schema(example = "52.3731") double pickupLat,
            @Schema(example = "4.8926") double pickupLng,
            @Schema(example = "20 Prinsengracht, Amsterdam") String deliveryAddress,
            @Schema(example = "52.3667") double deliveryLat,
            @Schema(example = "4.8830") double deliveryLng,

            @Schema(description = "The offer lapses at this time.", example = "2026-01-15T12:03:00Z")
            Instant expiresAt) {}

    /** The offer after an accept or a decline, also when the command was a repeat. */
    record OfferResponse(
            @Schema(example = "31") long offerId,
            @Schema(example = "1042") long orderId,
            OfferStatus status,
            @Schema(example = "2026-01-15T12:01:30Z") Instant respondedAt) {}
}
