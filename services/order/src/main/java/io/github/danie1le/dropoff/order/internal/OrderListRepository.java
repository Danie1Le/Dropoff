package io.github.danie1le.dropoff.order.internal;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.support.PageableExecutionUtils;

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

    /**
     * A customer's orders, newest first (M7-3). Both queries walk {@code orders_customer_id_created_at_id_idx}
     * backwards, so neither sorts. The first page reads its rows straight from that scan. A later page is a deferred
     * join: an index-only scan skips the offset and picks the page's ids, and only those rows are read from the table.
     * A plain {@code OFFSET} reads every skipped row from the table first; the join's 20 primary-key probes cost more
     * than that only when nothing is skipped. The count runs only when the page doesn't already give the total
     * ({@link PageableExecutionUtils}).
     */
    default Page<Order> findForCustomer(long customerId, Pageable page) {
        List<Order> content = page.getOffset() == 0
                ? findFirstPageForCustomer(customerId, page.getPageSize())
                : findPageForCustomer(customerId, page.getOffset(), page.getPageSize());
        return PageableExecutionUtils.getPage(content, page, () -> countForCustomer(customerId));
    }

    /** The first page of {@link #findForCustomer}; callers use that. */
    @Query(
            value = "SELECT * FROM orders WHERE customer_id = :customerId ORDER BY created_at DESC, id DESC"
                    + " LIMIT :limit",
            nativeQuery = true)
    List<Order> findFirstPageForCustomer(@Param("customerId") long customerId, @Param("limit") int limit);

    /** A later page of {@link #findForCustomer}; callers use that. */
    @Query(
            value = "SELECT o.* FROM orders o JOIN (SELECT id FROM orders WHERE customer_id = :customerId"
                    + " ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit) page ON page.id = o.id"
                    + " ORDER BY o.created_at DESC, o.id DESC",
            nativeQuery = true)
    List<Order> findPageForCustomer(
            @Param("customerId") long customerId, @Param("offset") long offset, @Param("limit") int limit);

    /** The total for {@link #findForCustomer}; callers use that. */
    @Query(value = "SELECT count(*) FROM orders WHERE customer_id = :customerId", nativeQuery = true)
    long countForCustomer(@Param("customerId") long customerId);
}
