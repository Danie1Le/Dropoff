package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import io.github.danie1le.dropoff.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The path-scoped order commands (ADR-0005). Each command has no body and returns
 * 200 with the order: a wrong caller gets 404, an illegal step 409, a repeat is a no-op.
 */
@RestController
class OrderCommandController {

    private final OrderCommandService service;

    OrderCommandController(OrderCommandService service) {
        this.service = service;
    }

    @ProblemResponse(status = 404, codes = "ORDER_NOT_FOUND")
    @ProblemResponse(status = 409, codes = "INVALID_STATUS_TRANSITION")
    @Operation(
            summary = "Merchant accepts an order",
            description = "PLACED to ACCEPTED, by the order merchant. Repeating the command is a no-op.")
    @PostMapping("/merchants/{merchantId}/orders/{orderId}/accept")
    OrderResponse accept(@PathVariable long merchantId, @PathVariable long orderId) {
        return service.byMerchant(merchantId, orderId, OrderCommand.ACCEPT);
    }

    @ProblemResponse(status = 404, codes = "ORDER_NOT_FOUND")
    @ProblemResponse(status = 409, codes = "INVALID_STATUS_TRANSITION")
    @Operation(
            summary = "Merchant starts preparing an order",
            description = "ACCEPTED to PREPARING. Repeating the command is a no-op.")
    @PostMapping("/merchants/{merchantId}/orders/{orderId}/start-preparing")
    OrderResponse startPreparing(@PathVariable long merchantId, @PathVariable long orderId) {
        return service.byMerchant(merchantId, orderId, OrderCommand.START_PREPARING);
    }

    @ProblemResponse(status = 404, codes = "ORDER_NOT_FOUND")
    @ProblemResponse(status = 409, codes = "INVALID_STATUS_TRANSITION")
    @Operation(
            summary = "Merchant marks an order ready",
            description = "PREPARING to READY. Repeating the command is a no-op.")
    @PostMapping("/merchants/{merchantId}/orders/{orderId}/ready")
    OrderResponse ready(@PathVariable long merchantId, @PathVariable long orderId) {
        return service.byMerchant(merchantId, orderId, OrderCommand.MARK_READY);
    }

    @ProblemResponse(status = 404, codes = "ORDER_NOT_FOUND")
    @ProblemResponse(status = 409, codes = "INVALID_STATUS_TRANSITION")
    @Operation(
            summary = "Merchant cancels an order",
            description = "Allowed only from PLACED or ACCEPTED. Repeating the command is a no-op.")
    @PostMapping("/merchants/{merchantId}/orders/{orderId}/cancel")
    OrderResponse merchantCancel(@PathVariable long merchantId, @PathVariable long orderId) {
        return service.byMerchant(merchantId, orderId, OrderCommand.CANCEL);
    }

    @ProblemResponse(status = 404, codes = "ORDER_NOT_FOUND")
    @ProblemResponse(status = 409, codes = "INVALID_STATUS_TRANSITION")
    @Operation(
            summary = "Customer cancels an order",
            description = "Allowed only from PLACED or ACCEPTED. Repeating the command is a no-op.")
    @PostMapping("/customers/{customerId}/orders/{orderId}/cancel")
    OrderResponse customerCancel(@PathVariable long customerId, @PathVariable long orderId) {
        return service.byCustomer(customerId, orderId, OrderCommand.CANCEL);
    }

    @ProblemResponse(status = 404, codes = "ORDER_NOT_FOUND")
    @ProblemResponse(status = 409, codes = "INVALID_STATUS_TRANSITION")
    @Operation(
            summary = "Courier picks up an order",
            description = "READY to PICKED_UP, by the assigned courier. Repeating the command is a no-op.")
    @PostMapping("/couriers/{courierId}/orders/{orderId}/pick-up")
    OrderResponse pickUp(@PathVariable long courierId, @PathVariable long orderId) {
        return service.byCourier(courierId, orderId, OrderCommand.PICK_UP);
    }

    @ProblemResponse(status = 404, codes = "ORDER_NOT_FOUND")
    @ProblemResponse(status = 409, codes = "INVALID_STATUS_TRANSITION")
    @Operation(
            summary = "Courier delivers an order",
            description = "PICKED_UP to DELIVERED, by the assigned courier. Repeating the command is a no-op.")
    @PostMapping("/couriers/{courierId}/orders/{orderId}/deliver")
    OrderResponse deliver(@PathVariable long courierId, @PathVariable long orderId) {
        return service.byCourier(courierId, orderId, OrderCommand.DELIVER);
    }
}
