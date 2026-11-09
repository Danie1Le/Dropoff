package io.github.danie1le.dropoff.order.internal;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * Both queries walk {@code orders_customer_id_created_at_id_idx} backwards, so neither sorts. The first page reads its
 * rows straight from that scan. A later page is a deferred join: an index-only scan skips the offset and picks the
 * page's ids, and only those rows are read from the table. With nothing to skip the join only adds work. The count
 * runs only when the page doesn't already give the total. See docs/perf/README.md, "M7-3".
 */
class OrderListRepositoryCustomImpl implements OrderListRepositoryCustom {

    private final EntityManager entityManager;

    OrderListRepositoryCustomImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<Order> findForCustomer(long customerId, Pageable page) {
        List<Order> content = page.getOffset() == 0
                ? firstPage(customerId, page.getPageSize())
                : laterPage(customerId, page.getOffset(), page.getPageSize());
        return PageableExecutionUtils.getPage(content, page, () -> count(customerId));
    }

    @SuppressWarnings("unchecked")
    private List<Order> firstPage(long customerId, int limit) {
        return entityManager
                .createNativeQuery(
                        "SELECT * FROM orders WHERE customer_id = :customerId ORDER BY created_at DESC, id DESC"
                                + " LIMIT :limit",
                        Order.class)
                .setParameter("customerId", customerId)
                .setParameter("limit", limit)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Order> laterPage(long customerId, long offset, int limit) {
        return entityManager
                .createNativeQuery(
                        "SELECT o.* FROM orders o JOIN (SELECT id FROM orders WHERE customer_id = :customerId"
                                + " ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit) page"
                                + " ON page.id = o.id ORDER BY o.created_at DESC, o.id DESC",
                        Order.class)
                .setParameter("customerId", customerId)
                .setParameter("offset", offset)
                .setParameter("limit", limit)
                .getResultList();
    }

    private long count(long customerId) {
        return ((Number) entityManager
                        .createNativeQuery("SELECT count(*) FROM orders WHERE customer_id = :customerId")
                        .setParameter("customerId", customerId)
                        .getSingleResult())
                .longValue();
    }
}
