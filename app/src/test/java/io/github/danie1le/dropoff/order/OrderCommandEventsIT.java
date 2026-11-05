package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.order.OrderCommandApiIT.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Cancelling publishes {@link OrderCancelled}; marking ready publishes {@link OrderReady} (M2-5). */
@SpringBootTest
@AutoConfigureMockMvc
@EnableScenarios
@Import(TestcontainersConfiguration.class)
class OrderCommandEventsIT {

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;

    OrderCommandEventsIT(@Autowired MockMvcTester mvc, @Autowired JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @AfterEach
    void cleanUp() {
        OrderCommandApiIT.deleteAll(jdbc);
    }

    private void post(String uri, long id, long orderId) {
        assertThat(mvc.post().uri(uri, id, orderId).exchange()).hasStatusOk();
    }

    @Test
    void publishesOrderCancelled(Scenario scenario) {
        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        scenario.stimulate(() -> post("/api/v1/customers/{id}/orders/{o}/cancel", f.customerId(), f.orderId()))
                .andWaitForEventOfType(OrderCancelled.class)
                .matching(e -> e.orderId() == f.orderId())
                .toArriveAndVerify(e -> {
                    assertThat(e.merchantId()).isEqualTo(f.merchantId());
                    assertThat(e.actor()).isEqualTo("CUSTOMER");
                    assertThat(e.eventId()).isNotNull();
                    assertThat(e.occurredAt()).isNotNull();
                });
    }

    @Test
    void publishesOrderReady(Scenario scenario) {
        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        post("/api/v1/merchants/{id}/orders/{o}/accept", f.merchantId(), f.orderId());
        post("/api/v1/merchants/{id}/orders/{o}/start-preparing", f.merchantId(), f.orderId());
        scenario.stimulate(() -> post("/api/v1/merchants/{id}/orders/{o}/ready", f.merchantId(), f.orderId()))
                .andWaitForEventOfType(OrderReady.class)
                .matching(e -> e.orderId() == f.orderId())
                .toArriveAndVerify(e -> {
                    assertThat(e.merchantId()).isEqualTo(f.merchantId());
                    assertThat(e.merchantName()).isEqualTo("Taco Shop");
                    assertThat(e.merchantAddress()).isEqualTo("100 Congress Ave");
                    assertThat(e.pickupLat()).isEqualTo(30.2672);
                    assertThat(e.pickupLng()).isEqualTo(-97.7431);
                    assertThat(e.deliveryAddress()).isEqualTo("200 Lavaca St");
                    assertThat(e.deliveryLat()).isEqualTo(30.2669);
                    assertThat(e.deliveryLng()).isEqualTo(-97.7466);
                    assertThat(e.readyAt()).isNotNull();
                    assertThat(e.eventId()).isNotNull();
                    assertThat(e.occurredAt()).isEqualTo(e.readyAt());
                });
    }
}
