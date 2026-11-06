package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

/** Order list endpoints (M2-6) end to end. Each test rolls back. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class OrderListApiIT {

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;

    OrderListApiIT(@Autowired MockMvcTester mvc, @Autowired JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private long order(long customerId, long merchantId, long itemId, String status, String createdAt) {
        long id = jdbc.sql("INSERT INTO orders (customer_id, merchant_id, status, total_cents, delivery_address,"
                        + " delivery_lat, delivery_lng, idempotency_key, request_hash, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 399, '200 Lavaca St', 30.2669, -97.7466, gen_random_uuid()::text,"
                        + " repeat('a', 64), ?::timestamptz, ?::timestamptz) RETURNING id")
                .params(customerId, merchantId, status, createdAt, createdAt)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO order_lines (order_id, menu_item_id, quantity, price_cents) VALUES (?, ?, 1, 399)")
                .params(id, itemId)
                .update();
        return id;
    }

    @Test
    void merchantListFiltersByStatusAndReturnsOrderBodies() {
        long c = PlaceOrderApiIT.customer(jdbc);
        long m = PlaceOrderApiIT.merchant(jdbc, true);
        long item = PlaceOrderApiIT.item(jdbc, m, 399, true);
        long placed = order(c, m, item, "PLACED", "2026-01-01T12:00:00Z");
        order(c, m, item, "ACCEPTED", "2026-01-01T10:00:00Z");

        var result =
                mvc.get().uri("/api/v1/merchants/{id}/orders?status=PLACED", m).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.content[0].id")
                .asNumber()
                .isEqualTo((int) placed);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.content[0].lines[0].priceCents")
                .asNumber()
                .isEqualTo(399);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .asNumber()
                .isEqualTo(1);
    }

    @Test
    void customerListIsNewestFirst() {
        long c = PlaceOrderApiIT.customer(jdbc);
        long m = PlaceOrderApiIT.merchant(jdbc, true);
        long item = PlaceOrderApiIT.item(jdbc, m, 399, true);
        order(c, m, item, "PLACED", "2026-01-01T10:00:00Z");
        long newer = order(c, m, item, "PLACED", "2026-01-01T12:00:00Z");

        var result = mvc.get().uri("/api/v1/customers/{id}/orders", c).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.content[0].id")
                .asNumber()
                .isEqualTo((int) newer);
    }

    @Test
    void oversizedPageIsRejected() {
        var result = mvc.get().uri("/api/v1/customers/1/orders?size=101").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownMerchantIsNotFound() {
        var result = mvc.get().uri("/api/v1/merchants/999999999/orders").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MERCHANT_NOT_FOUND");
    }

    @Test
    void unknownCustomerIsNotFound() {
        var result = mvc.get().uri("/api/v1/customers/999999999/orders").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CUSTOMER_NOT_FOUND");
    }

    @Test
    void invalidStatusIsAValidationProblem() {
        long m = PlaceOrderApiIT.merchant(jdbc, true);

        var result =
                mvc.get().uri("/api/v1/merchants/{id}/orders?status=BOGUS", m).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentType("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("status");
    }
}
