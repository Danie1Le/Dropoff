package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.dispatch.internal.OfferResponses.CurrentOffer;
import io.github.danie1le.dropoff.dispatch.internal.OfferResponses.OfferResponse;
import io.github.danie1le.dropoff.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A courier's offers under {@code /api/v1/couriers/{courierId}/offers} (M3-3, ticket #11). The courier app polls
 * {@code current}; there is no push for offers in v1.
 */
@RestController
@RequestMapping("/couriers/{courierId}/offers")
class OfferController {

    private final OfferService service;

    OfferController(OfferService service) {
        this.service = service;
    }

    @ApiResponse(
            responseCode = "200",
            description = "The courier's open offer.",
            content = @Content(schema = @Schema(implementation = CurrentOffer.class)))
    @ApiResponse(responseCode = "204", description = "The courier has no open offer.")
    @ProblemResponse(status = 404, codes = "COURIER_NOT_FOUND")
    @Operation(summary = "Get the courier open offer", description = "Couriers poll this; 204 means no open offer.")
    @GetMapping("/current")
    ResponseEntity<CurrentOffer> current(@PathVariable long courierId) {
        return service.current(courierId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @ProblemResponse(
            status = 404,
            codes = {"COURIER_NOT_FOUND", "OFFER_NOT_FOUND"})
    @ProblemResponse(
            status = 409,
            codes = {"OFFER_CLOSED", "COURIER_NOT_BUSY"})
    @Operation(
            summary = "Accept an offer",
            description = "Assigns the order to the courier. Repeating an accepted offer returns it unchanged.")
    @PostMapping("/{offerId}/accept")
    OfferResponse accept(@PathVariable long courierId, @PathVariable long offerId) {
        return service.accept(courierId, offerId);
    }

    @ProblemResponse(
            status = 404,
            codes = {"COURIER_NOT_FOUND", "OFFER_NOT_FOUND"})
    @ProblemResponse(status = 409, codes = "OFFER_CLOSED")
    @Operation(
            summary = "Decline an offer",
            description =
                    "Closes the offer so dispatch can try another courier. Repeating a decline returns it unchanged.")
    @PostMapping("/{offerId}/decline")
    OfferResponse decline(@PathVariable long courierId, @PathVariable long offerId) {
        return service.decline(courierId, offerId);
    }
}
