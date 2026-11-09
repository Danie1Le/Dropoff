package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import io.github.danie1le.dropoff.order.internal.OrderRequests.PlaceOrder;
import io.github.danie1le.dropoff.order.internal.OrderService.Placement;
import io.github.danie1le.dropoff.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Order endpoints under {@code /api/v1/orders}. */
@RestController
@RequestMapping("/orders")
class OrderController {

    private static final String REPLAYED = "Idempotent-Replayed";

    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    @ProblemResponse(status = 404, codes = "ORDER_NOT_FOUND")
    @Operation(summary = "Get an order")
    @GetMapping("/{id}")
    OrderResponse get(@PathVariable long id) {
        return service.get(id);
    }

    /**
     * Places an order. The {@code Idempotency-Key} header is required; a repeat of an earlier request with the same
     * key returns the original order with {@code Idempotent-Replayed: true} (M2-4, ticket #42). The key is declared
     * before the body so it is checked first ({@link IdempotencyKey}).
     */
    @ProblemResponse(status = 400, codes = "IDEMPOTENCY_KEY_REQUIRED")
    @ProblemResponse(
            status = 422,
            codes = {
                "CUSTOMER_UNKNOWN",
                "MERCHANT_UNKNOWN",
                "MERCHANT_CLOSED",
                "MENU_ITEM_UNKNOWN",
                "MENU_ITEM_WRONG_MERCHANT",
                "MENU_ITEM_UNAVAILABLE",
                "IDEMPOTENCY_KEY_REUSED"
            })
    @ApiResponse(
            responseCode = "201",
            description = "Created.",
            headers = {
                @Header(name = HttpHeaders.LOCATION, description = "URL of the new order."),
                @Header(
                        name = REPLAYED,
                        description = "true when this is a replay of an earlier request with the same key.")
            })
    @Operation(
            summary = "Place an order",
            description =
                    "Prices, total and delivery address come from the server. The Idempotency-Key header makes a retry safe.")
    @PostMapping
    ResponseEntity<OrderResponse> place(
            @Parameter(
                            in = ParameterIn.HEADER,
                            name = IdempotencyKey.HEADER,
                            required = true,
                            description = "Client-chosen key, at most 255 characters; a retry with the same key and"
                                    + " body replays the order with Idempotent-Replayed: true.")
                    @IdempotencyKey
                    String idempotencyKey,
            @Valid @RequestBody PlaceOrder request) {
        Placement placement;
        try {
            placement = service.place(idempotencyKey, request);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same key committed first; its order is now visible.
            placement = service.replay(idempotencyKey, request).orElseThrow(() -> e);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(placement.orderId())
                .toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity.created(location);
        if (placement.replayed()) {
            response.header(REPLAYED, "true");
        }
        return response.body(placement.body());
    }
}
