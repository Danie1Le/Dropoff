package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Order commands and GET /orders/{id} end to end, against a real Postgres (M2-5, ADR-0005). Not rolled back, since
 * the race test needs real commits; each test deletes its rows instead, so other ITs see a clean table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderCommandApiIT {

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;

    OrderCommandApiIT(@Autowired MockMvcTester mvc, @Autowired JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    record Fixture(long customerId, long merchantId, long orderId) {}

    @AfterEach
    void cleanUp() {
        deleteAll(jdbc);
    }

    /**
     * Deletes every order, customer and merchant, and dispatch's rows for those orders; only for ITs that commit.
     *
     * <p>Marking an order ready starts dispatch's async {@code OrderReady} listener (M3-2), which inserts a {@code
     * dispatch_orders} row. It first waits for event deliveries still in flight, so that insert never lands between the
     * deletes. Failed deliveries are not waited for; their registry rows are dropped with the rest.
     */
    static void deleteAll(JdbcClient jdbc) {
        Awaitility.await()
                .pollDelay(Duration.ZERO)
                .atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL"
                                        + " AND status IS DISTINCT FROM 'FAILED'")
                                .query(Long.class)
                                .single()
                        == 0);
        for (String table : List.of(
                "event_publication",
                "offers",
                "dispatch_orders",
                "order_events",
                "order_lines",
                "orders",
                "customers",
                "menu_items",
                "merchants")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    /** Places a real order through the API, so it has lines and its PLACED event row. */
    static Fixture placed(MockMvcTester mvc, JdbcClient jdbc) {
        long customerId = OrderFixtures.customer(jdbc);
        long merchantId = OrderFixtures.merchant(jdbc, true);
        long taco = OrderFixtures.item(jdbc, merchantId, 399, true);
        MvcTestResult result = mvc.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"customerId\":%d,\"merchantId\":%d,\"lines\":[{\"menuItemId\":%d,\"quantity\":1}]}"
                        .formatted(customerId, merchantId, taco))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        long orderId = jdbc.sql("SELECT id FROM orders WHERE customer_id = ?")
                .param(customerId)
                .query(Long.class)
                .single();
        return new Fixture(customerId, merchantId, orderId);
    }

    private MvcTestResult merchant(Fixture f, String command) {
        return mvc.post()
                .uri("/api/v1/merchants/{id}/orders/{orderId}/" + command, f.merchantId(), f.orderId())
                .exchange();
    }

    private MvcTestResult customerCancel(Fixture f) {
        return mvc.post()
                .uri("/api/v1/customers/{id}/orders/{orderId}/cancel", f.customerId(), f.orderId())
                .exchange();
    }

    private static void assertStatus(MvcTestResult result, String status) {
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status);
    }

    private static void assertError(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    }

    private List<String> events(Fixture f) {
        return jdbc.sql("SELECT coalesce(from_status, '-') || '>' || to_status || ':' || actor FROM order_events"
                        + " WHERE order_id = ? ORDER BY id")
                .param(f.orderId())
                .query(String.class)
                .list();
    }

    @Test
    void getsAnOrder() {
        Fixture f = placed(mvc, jdbc);
        MvcTestResult result = mvc.get().uri("/api/v1/orders/{id}", f.orderId()).exchange();
        assertStatus(result, "PLACED");
        assertThat(result).bodyJson().extractingPath("$.totalCents").isEqualTo(399);
        assertThat(result).bodyJson().extractingPath("$.lines[0].priceCents").isEqualTo(399);
    }

    @Test
    void unknownOrderIs404() {
        assertError(
                mvc.get().uri("/api/v1/orders/{id}", Long.MAX_VALUE).exchange(),
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND");
    }

    @Test
    void merchantWalksOrderToReady() {
        Fixture f = placed(mvc, jdbc);
        assertStatus(merchant(f, "accept"), "ACCEPTED");
        assertStatus(merchant(f, "start-preparing"), "PREPARING");
        assertStatus(merchant(f, "ready"), "READY");
        assertThat(events(f))
                .containsExactly(
                        "->PLACED:CUSTOMER",
                        "PLACED>ACCEPTED:MERCHANT",
                        "ACCEPTED>PREPARING:MERCHANT",
                        "PREPARING>READY:MERCHANT");
    }

    @Test
    void merchantCancels() {
        Fixture f = placed(mvc, jdbc);
        assertStatus(merchant(f, "accept"), "ACCEPTED");
        assertStatus(merchant(f, "cancel"), "CANCELLED");
        assertThat(events(f)).last().isEqualTo("ACCEPTED>CANCELLED:MERCHANT");
    }

    @Test
    void customerCancels() {
        Fixture f = placed(mvc, jdbc);
        assertStatus(customerCancel(f), "CANCELLED");
        assertThat(events(f)).containsExactly("->PLACED:CUSTOMER", "PLACED>CANCELLED:CUSTOMER");
    }

    @Test
    void repeatedCommandIsNoOp() {
        Fixture f = placed(mvc, jdbc);
        assertStatus(merchant(f, "accept"), "ACCEPTED");
        assertStatus(merchant(f, "accept"), "ACCEPTED");
        assertThat(events(f)).hasSize(2);
    }

    @Test
    void illegalStepIs409() {
        Fixture f = placed(mvc, jdbc);
        assertError(merchant(f, "ready"), HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        assertThat(events(f)).hasSize(1);
    }

    @Test
    void cancelOncePreparingIs409() {
        Fixture f = placed(mvc, jdbc);
        assertStatus(merchant(f, "accept"), "ACCEPTED");
        assertStatus(merchant(f, "start-preparing"), "PREPARING");
        assertError(customerCancel(f), HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
    }

    @Test
    void wrongMerchantIs404() {
        Fixture f = placed(mvc, jdbc);
        long other = OrderFixtures.merchant(jdbc, true);
        assertError(
                merchant(new Fixture(f.customerId(), other, f.orderId()), "accept"),
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND");
    }

    @Test
    void wrongCustomerIs404() {
        Fixture f = placed(mvc, jdbc);
        long other = OrderFixtures.customer(jdbc);
        assertError(
                customerCancel(new Fixture(other, f.merchantId(), f.orderId())),
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND");
    }

    @Test
    void twoRacingCancelsWriteOneEvent() throws Exception {
        Fixture f = placed(mvc, jdbc);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcTestResult> byMerchant = () -> {
            start.await();
            return merchant(f, "cancel");
        };
        Callable<MvcTestResult> byCustomer = () -> {
            start.await();
            return customerCancel(f);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcTestResult> a = pool.submit(byMerchant);
            Future<MvcTestResult> b = pool.submit(byCustomer);
            start.countDown();
            assertStatus(a.get(), "CANCELLED");
            assertStatus(b.get(), "CANCELLED");
        } finally {
            pool.shutdown();
        }
        List<String> events = events(f);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).startsWith("PLACED>CANCELLED:");
    }
}
