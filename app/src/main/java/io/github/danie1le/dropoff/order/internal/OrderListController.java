package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.PageParams;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order lists (M2-6): {@code GET /api/v1/merchants/{id}/orders?status=&page=} (oldest first) and
 * {@code GET /api/v1/customers/{id}/orders?page=} (newest first). An unknown merchant or customer gets an empty page.
 */
@RestController
class OrderListController {

    private final OrderLists lists;

    OrderListController(OrderLists lists) {
        this.lists = lists;
    }

    @GetMapping("/merchants/{id}/orders")
    PagedModel<OrderResponse> forMerchant(
            @PathVariable long id,
            @RequestParam(required = false) @Nullable OrderStatus status,
            @Valid PageParams params) {
        return lists.forMerchant(id, status, params);
    }

    @GetMapping("/customers/{id}/orders")
    PagedModel<OrderResponse> forCustomer(@PathVariable long id, @Valid PageParams params) {
        return lists.forCustomer(id, params);
    }

    /** Maps inside a read-only transaction so each order's lines load. The SQL owns the sort, so paging is unsorted. */
    @Service
    static class OrderLists {

        private final OrderListRepository orders;

        OrderLists(OrderListRepository orders) {
            this.orders = orders;
        }

        @Transactional(readOnly = true)
        PagedModel<OrderResponse> forMerchant(long merchantId, @Nullable OrderStatus status, PageParams params) {
            return new PagedModel<>(orders.findForMerchant(merchantId, status, params.toPageable(Sort.unsorted()))
                    .map(OrderResponse::of));
        }

        @Transactional(readOnly = true)
        PagedModel<OrderResponse> forCustomer(long customerId, PageParams params) {
            return new PagedModel<>(orders.findForCustomer(customerId, params.toPageable(Sort.unsorted()))
                    .map(OrderResponse::of));
        }
    }
}
