package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/orders/{id}} and the path-scoped order commands (ADR-0005). Each command has no body and returns
 * 200 with the order: a wrong caller gets 404, an illegal step 409, a repeat is a no-op.
 */
@RestController
class OrderCommandController {

    private final OrderCommandService service;

    OrderCommandController(OrderCommandService service) {
        this.service = service;
    }

    @GetMapping("/orders/{id}")
    OrderResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/merchants/{merchantId}/orders/{orderId}/accept")
    OrderResponse accept(@PathVariable long merchantId, @PathVariable long orderId) {
        return service.byMerchant(merchantId, orderId, OrderCommand.ACCEPT);
    }

    @PostMapping("/merchants/{merchantId}/orders/{orderId}/start-preparing")
    OrderResponse startPreparing(@PathVariable long merchantId, @PathVariable long orderId) {
        return service.byMerchant(merchantId, orderId, OrderCommand.START_PREPARING);
    }

    @PostMapping("/merchants/{merchantId}/orders/{orderId}/ready")
    OrderResponse ready(@PathVariable long merchantId, @PathVariable long orderId) {
        return service.byMerchant(merchantId, orderId, OrderCommand.MARK_READY);
    }

    @PostMapping("/merchants/{merchantId}/orders/{orderId}/cancel")
    OrderResponse merchantCancel(@PathVariable long merchantId, @PathVariable long orderId) {
        return service.byMerchant(merchantId, orderId, OrderCommand.CANCEL);
    }

    @PostMapping("/customers/{customerId}/orders/{orderId}/cancel")
    OrderResponse customerCancel(@PathVariable long customerId, @PathVariable long orderId) {
        return service.byCustomer(customerId, orderId, OrderCommand.CANCEL);
    }
}
