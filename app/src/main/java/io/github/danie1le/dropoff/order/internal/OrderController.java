package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import io.github.danie1le.dropoff.order.internal.OrderRequests.PlaceOrder;
import io.github.danie1le.dropoff.order.internal.OrderService.Placement;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
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

    /**
     * Places an order. The {@code Idempotency-Key} header is required; a repeat of an earlier request with the same
     * key returns the original order with {@code Idempotent-Replayed: true} (M2-4, ticket #42). The key is declared
     * before the body so it is checked first ({@link IdempotencyKey}).
     */
    @PostMapping
    ResponseEntity<OrderResponse> place(@IdempotencyKey String idempotencyKey, @Valid @RequestBody PlaceOrder request) {
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
