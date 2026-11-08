package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.order.OrderCommandApiIT.Fixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * order-service's metrics on {@code /actuator/prometheus} (M6-1, tickets #13, #34, #42). Not rolled back: the counters
 * move only after commit, so each test deletes its rows instead. Counters are shared by every test in the context, so
 * each test compares against the value it read first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderMetricsIT {

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    OrderMetricsIT(@Autowired MockMvcTester mvc, @Autowired JdbcClient jdbc, @Autowired MeterRegistry meters) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    @AfterEach
    void cleanUp() {
        OrderCommandApiIT.deleteAll(jdbc);
    }

    private String scrape() throws Exception {
        MvcTestResult result = mvc.get().uri("/actuator/prometheus").exchange();
        assertThat(result).hasStatusOk();
        return result.getResponse().getContentAsString();
    }

    private double counter(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    @Test
    void theScrapeShowsEveryOrderMetric() throws Exception {
        assertThat(mvc.get().uri("/api/v1/orders/{id}", Long.MAX_VALUE).exchange())
                .hasStatus4xxClientError();

        assertThat(scrape())
                .contains("dropoff_orders_placed_total ")
                .contains("dropoff_orders_delivered_total ")
                .contains("dropoff_orders_cancelled_total{actor=\"CUSTOMER\"}")
                .contains("dropoff_orders_cancelled_total{actor=\"MERCHANT\"}")
                .contains("dropoff_orders_cancelled_total{actor=\"SYSTEM\"}")
                .contains("dropoff_orders_in_preparation ")
                .contains("dropoff_oldest_in_preparation_age_seconds ")
                .contains("dropoff_dlt_messages_total{topic=\"order-events\"}")
                .contains("dropoff_dlt_messages_total{topic=\"courier-events\"}")
                .contains("http_server_requests_seconds_bucket{");
    }

    @Test
    void placingAndCancellingMoveTheirCounters() {
        double placed = counter("dropoff.orders.placed");
        double byCustomer = counter("dropoff.orders.cancelled", "actor", "CUSTOMER");

        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        assertThat(counter("dropoff.orders.placed")).isEqualTo(placed + 1);

        assertThat(mvc.post()
                        .uri("/api/v1/customers/{id}/orders/{orderId}/cancel", f.customerId(), f.orderId())
                        .exchange())
                .hasStatusOk();
        assertThat(counter("dropoff.orders.cancelled", "actor", "CUSTOMER")).isEqualTo(byCustomer + 1);
    }

    @Test
    void aRefusedCommandMovesNoCounter() {
        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        double delivered = counter("dropoff.orders.delivered");

        // A PLACED order cannot be delivered: 404 for a courier who is not on it, and nothing is counted.
        mvc.post()
                .uri("/api/v1/couriers/{c}/orders/{o}/deliver", OrderFixtures.courierId(), f.orderId())
                .exchange();

        assertThat(counter("dropoff.orders.delivered")).isEqualTo(delivered);
    }

    @Test
    void deliveringMovesItsCounter() {
        long courierId = OrderFixtures.courierId();
        long orderId = OrderFixtures.order(
                jdbc,
                OrderFixtures.customer(jdbc),
                OrderFixtures.merchant(jdbc, true),
                "READY",
                399,
                Instant.now().toString());
        jdbc.sql("UPDATE orders SET courier_id = ?, status = 'PICKED_UP' WHERE id = ?")
                .params(courierId, orderId)
                .update();
        double delivered = counter("dropoff.orders.delivered");

        assertThat(mvc.post()
                        .uri("/api/v1/couriers/{c}/orders/{o}/deliver", courierId, orderId)
                        .exchange())
                .hasStatusOk();

        assertThat(counter("dropoff.orders.delivered")).isEqualTo(delivered + 1);
    }

    @Test
    void theInPreparationGaugesCountFromTheAcceptance() {
        double before = gauge("dropoff.orders.in.preparation");
        long orderId = OrderFixtures.order(
                jdbc,
                OrderFixtures.customer(jdbc),
                OrderFixtures.merchant(jdbc, true),
                "PREPARING",
                399,
                Instant.now().minusSeconds(600).toString());
        jdbc.sql("INSERT INTO order_events (order_id, from_status, to_status, actor, created_at)"
                        + " VALUES (?, 'PLACED', 'ACCEPTED', 'MERCHANT', now() - interval '90 seconds')")
                .param(orderId)
                .update();

        assertThat(gauge("dropoff.orders.in.preparation")).isEqualTo(before + 1);
        // Counted from the ACCEPTED event, 90 s ago, not from placing, 600 s ago.
        assertThat(gauge("dropoff.oldest.in.preparation.age")).isBetween(90.0, 300.0);
    }
}
