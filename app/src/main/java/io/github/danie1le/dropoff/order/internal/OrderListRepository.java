package io.github.danie1le.dropoff.order.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Merchant and customer order lists (M2-6). Hand-written SQL (ADR-0002) backed by the V5 indexes. The SQL owns the
 * sort, so callers pass an unsorted {@link Pageable}; {@code id} breaks ties between orders placed at the same instant.
 */
interface OrderListRepository extends Repository<Order, Long> {

    /** A merchant's orders, oldest first, optionally only those in one status. */
    default Page<Order> findForMerchant(long merchantId, @Nullable OrderStatus status, Pageable page) {
        return findForMerchantByStatusName(merchantId, status == null ? null : status.name(), page);
    }

    @Query(
            value = "SELECT * FROM orders WHERE merchant_id = :merchantId"
                    + " AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))"
                    + " ORDER BY created_at, id",
            countQuery = "SELECT count(*) FROM orders WHERE merchant_id = :merchantId"
                    + " AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))",
            nativeQuery = true)
    Page<Order> findForMerchantByStatusName(
            @Param("merchantId") long merchantId, @Param("status") @Nullable String status, Pageable page);

    /** A customer's orders, newest first. */
    @Query(
            value = "SELECT * FROM orders WHERE customer_id = :customerId ORDER BY created_at DESC, id DESC",
            countQuery = "SELECT count(*) FROM orders WHERE customer_id = :customerId",
            nativeQuery = true)
    Page<Order> findForCustomer(@Param("customerId") long customerId, Pageable page);
}
