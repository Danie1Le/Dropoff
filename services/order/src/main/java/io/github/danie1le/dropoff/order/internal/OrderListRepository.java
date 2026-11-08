package io.github.danie1le.dropoff.order.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Merchant and customer order lists (M2-6). Hand-written SQL (ADR-0002). The SQL owns the sort, so callers pass an
 * unsorted {@link Pageable}; {@code id} breaks ties between orders placed at the same instant. The merchant list has
 * one query per shape, with no {@code IS NULL OR} filter, so each can use its index in a generic plan too:
 * {@code orders_merchant_id_created_at_idx} without a status, {@code orders_merchant_id_status_idx} with one.
 */
interface OrderListRepository extends Repository<Order, Long> {

    /** All of a merchant's orders, oldest first. */
    @Query(
            value = "SELECT * FROM orders WHERE merchant_id = :merchantId ORDER BY created_at, id",
            countQuery = "SELECT count(*) FROM orders WHERE merchant_id = :merchantId",
            nativeQuery = true)
    Page<Order> findForMerchant(@Param("merchantId") long merchantId, Pageable page);

    /** A merchant's orders in one status, oldest first. */
    @Query(
            value = "SELECT * FROM orders WHERE merchant_id = :merchantId AND status = :#{#status.name()}"
                    + " ORDER BY created_at, id",
            countQuery = "SELECT count(*) FROM orders WHERE merchant_id = :merchantId AND status = :#{#status.name()}",
            nativeQuery = true)
    Page<Order> findForMerchantInStatus(
            @Param("merchantId") long merchantId, @Param("status") OrderStatus status, Pageable page);

    /** A customer's orders, newest first. */
    @Query(
            value = "SELECT * FROM orders WHERE customer_id = :customerId ORDER BY created_at DESC, id DESC",
            countQuery = "SELECT count(*) FROM orders WHERE customer_id = :customerId",
            nativeQuery = true)
    Page<Order> findForCustomer(@Param("customerId") long customerId, Pageable page);
}
