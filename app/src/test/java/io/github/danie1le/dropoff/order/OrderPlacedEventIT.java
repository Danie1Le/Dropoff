package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Placing an order publishes {@link OrderPlaced} once it commits. */
@SpringBootTest
@AutoConfigureMockMvc
@EnableScenarios
@Import(TestcontainersConfiguration.class)
class OrderPlacedEventIT {

    @Test
    void publishesOrderPlaced(@Autowired MockMvcTester mvc, @Autowired JdbcClient jdbc, Scenario scenario) {
        long customerId = OrderFixtures.customer(jdbc);
        long merchantId = OrderFixtures.merchant(jdbc, true);
        long taco = OrderFixtures.item(jdbc, merchantId, 399, true);
        String body = "{\"customerId\":%d,\"merchantId\":%d,\"lines\":[{\"menuItemId\":%d,\"quantity\":2}]}"
                .formatted(customerId, merchantId, taco);

        scenario.stimulate(() -> assertThat(mvc.post()
                                .uri("/api/v1/orders")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .exchange())
                        .hasStatus(HttpStatus.CREATED))
                .andWaitForEventOfType(OrderPlaced.class)
                .matching(event -> event.customerId() == customerId)
                .toArriveAndVerify(event -> {
                    assertThat(event.merchantId()).isEqualTo(merchantId);
                    assertThat(event.totalCents()).isEqualTo(798);
                    assertThat(event.orderId()).isPositive();
                    assertThat(event.eventId()).isNotNull();
                    assertThat(event.occurredAt()).isNotNull();
                });
    }
}
