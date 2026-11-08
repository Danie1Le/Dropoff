package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.merchant.MerchantDirectory;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import io.github.danie1le.dropoff.web.PageParams;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order lists (M2-6). Maps inside a read-only transaction so each page's lines load (batched, see {@link Order}). The
 * SQL owns the sort, so paging is unsorted. An unknown merchant or customer is a 404, as on their other endpoints.
 */
@Service
class OrderListService {

    private final OrderListRepository orders;
    private final MerchantDirectory merchants;
    private final CustomerRepository customers;

    OrderListService(OrderListRepository orders, MerchantDirectory merchants, CustomerRepository customers) {
        this.orders = orders;
        this.merchants = merchants;
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    PagedModel<OrderResponse> forMerchant(long merchantId, @Nullable OrderStatus status, PageParams params) {
        if (merchants.findMerchant(merchantId).isEmpty()) {
            throw MerchantDirectory.notFound(merchantId);
        }
        Pageable page = params.toPageable();
        Page<Order> found = status == null
                ? orders.findForMerchant(merchantId, page)
                : orders.findForMerchantInStatus(merchantId, status, page);
        return new PagedModel<>(found.map(OrderResponse::of));
    }

    @Transactional(readOnly = true)
    PagedModel<OrderResponse> forCustomer(long customerId, PageParams params) {
        if (!customers.existsById(customerId)) {
            throw CustomerService.notFound(customerId);
        }
        return new PagedModel<>(
                orders.findForCustomer(customerId, params.toPageable()).map(OrderResponse::of));
    }
}
