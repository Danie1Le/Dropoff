package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.common.PageParams;
import io.github.danie1le.dropoff.merchant.MerchantCatalog;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
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
    private final MerchantCatalog catalog;
    private final CustomerRepository customers;

    OrderListService(OrderListRepository orders, MerchantCatalog catalog, CustomerRepository customers) {
        this.orders = orders;
        this.catalog = catalog;
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    PagedModel<OrderResponse> forMerchant(long merchantId, @Nullable OrderStatus status, PageParams params) {
        if (catalog.findMerchant(merchantId).isEmpty()) {
            throw new DomainException(
                    DomainException.Category.NOT_FOUND,
                    "MERCHANT_NOT_FOUND",
                    "Merchant " + merchantId + " does not exist.");
        }
        return new PagedModel<>(orders.findForMerchant(merchantId, status, params.toPageable(Sort.unsorted()))
                .map(OrderResponse::of));
    }

    @Transactional(readOnly = true)
    PagedModel<OrderResponse> forCustomer(long customerId, PageParams params) {
        if (!customers.existsById(customerId)) {
            throw CustomerService.notFound(customerId);
        }
        return new PagedModel<>(orders.findForCustomer(customerId, params.toPageable(Sort.unsorted()))
                .map(OrderResponse::of));
    }
}
