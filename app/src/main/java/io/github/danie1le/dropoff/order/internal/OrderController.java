package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import io.github.danie1le.dropoff.order.internal.OrderRequests.PlaceOrder;
import io.github.danie1le.dropoff.order.internal.OrderService.Placement;
import jakarta.validation.Valid;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Order endpoints under {@code /api/v1/orders}. */
@RestController
@RequestMapping("/orders")
class OrderController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String REPLAYED = "Idempotent-Replayed";
    /** The width of {@code orders.idempotency_key}. */
    private static final int MAX_KEY_LENGTH = 255;

    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    /**
     * Places an order. The {@code Idempotency-Key} header is required; a repeat of an earlier request with the same
     * key returns the original order with {@code Idempotent-Replayed: true} (M2-4, ticket #42).
     */
    @PostMapping
    ResponseEntity<OrderResponse> place(
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody PlaceOrder request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new DomainException(
                    DomainException.Category.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "The Idempotency-Key header is required and must be 1 to 255 characters.");
        }
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
