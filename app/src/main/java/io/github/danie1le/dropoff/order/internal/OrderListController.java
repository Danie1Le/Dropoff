package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import io.github.danie1le.dropoff.web.PageParams;
import io.github.danie1le.dropoff.web.ProblemResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order lists (M2-6): {@code GET /api/v1/merchants/{id}/orders?status=&page=} (oldest first) and
 * {@code GET /api/v1/customers/{id}/orders?page=} (newest first). An unknown merchant or customer is a 404.
 */
@RestController
class OrderListController {

    private final OrderListService lists;

    OrderListController(OrderListService lists) {
        this.lists = lists;
    }

    @ProblemResponse(status = 404, codes = "MERCHANT_NOT_FOUND")
    @GetMapping("/merchants/{id}/orders")
    PagedModel<OrderResponse> forMerchant(
            @PathVariable long id,
            @RequestParam(required = false) @Nullable OrderStatus status,
            @ParameterObject @Valid PageParams params) {
        return lists.forMerchant(id, status, params);
    }

    @ProblemResponse(status = 404, codes = "CUSTOMER_NOT_FOUND")
    @GetMapping("/customers/{id}/orders")
    PagedModel<OrderResponse> forCustomer(@PathVariable long id, @ParameterObject @Valid PageParams params) {
        return lists.forCustomer(id, params);
    }
}
