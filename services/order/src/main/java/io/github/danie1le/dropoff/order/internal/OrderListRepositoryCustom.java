package io.github.danie1le.dropoff.order.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** The customer order list, newest first. A fragment of {@link OrderListRepository}, so its helper queries stay private. */
interface OrderListRepositoryCustom {

    Page<Order> findForCustomer(long customerId, Pageable page);
}
