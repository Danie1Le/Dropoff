package io.github.danie1le.dropoff.order.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.order.OrderFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Merchant and customer order lists (M2-6), against a real Postgres. Each test rolls back. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class OrderListRepositoryIT {

    private final OrderListRepository orders;
    private final JdbcClient jdbc;

    OrderListRepositoryIT(@Autowired OrderListRepository orders, @Autowired JdbcClient jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
    }

    private long customer() {
        return OrderFixtures.customer(jdbc);
    }

    private long merchant() {
        return OrderFixtures.merchant(jdbc, true);
    }

    private long order(long customerId, long merchantId, String status, String createdAt) {
        return OrderFixtures.order(jdbc, customerId, merchantId, status, 0, createdAt);
    }

    private static List<Long> ids(Page<Order> page) {
        return page.getContent().stream().map(Order::id).toList();
    }

    @Test
    void merchantOrdersAreOldestFirstAndOnlyThatMerchants() {
        long c = customer();
        long m = merchant();
        long other = merchant();
        long newer = order(c, m, "PLACED", "2026-01-01T12:00:00Z");
        long older = order(c, m, "ACCEPTED", "2026-01-01T10:00:00Z");
        order(c, other, "PLACED", "2026-01-01T09:00:00Z");

        Page<Order> page = orders.findForMerchant(m, PageRequest.of(0, 20));

        assertThat(ids(page)).containsExactly(older, newer);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void merchantOrdersFilterByStatus() {
        long c = customer();
        long m = merchant();
        long placed = order(c, m, "PLACED", "2026-01-01T12:00:00Z");
        order(c, m, "ACCEPTED", "2026-01-01T10:00:00Z");

        Page<Order> page = orders.findForMerchantInStatus(m, OrderStatus.PLACED, PageRequest.of(0, 20));

        assertThat(ids(page)).containsExactly(placed);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void customerOrdersAreNewestFirstAndOnlyThatCustomers() {
        long c = customer();
        long otherCustomer = customer();
        long m = merchant();
        long older = order(c, m, "CANCELLED", "2026-01-01T10:00:00Z");
        long newer = order(c, m, "PLACED", "2026-01-01T12:00:00Z");
        order(otherCustomer, m, "PLACED", "2026-01-01T13:00:00Z");

        Page<Order> page = orders.findForCustomer(c, PageRequest.of(0, 20));

        assertThat(ids(page)).containsExactly(newer, older);
    }

    @Test
    void pagesSplitTheListAndCountEveryMatch() {
        long c = customer();
        long m = merchant();
        long first = order(c, m, "PLACED", "2026-01-01T10:00:00Z");
        long second = order(c, m, "PLACED", "2026-01-01T11:00:00Z");
        long third = order(c, m, "PLACED", "2026-01-01T12:00:00Z");

        Page<Order> page0 = orders.findForMerchant(m, PageRequest.of(0, 2));
        Page<Order> page1 = orders.findForMerchant(m, PageRequest.of(1, 2));
        Page<Order> customerPage1 = orders.findForCustomer(c, PageRequest.of(1, 2));

        assertThat(ids(page0)).containsExactly(first, second);
        assertThat(ids(page1)).containsExactly(third);
        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(ids(customerPage1)).containsExactly(first);
    }
}
