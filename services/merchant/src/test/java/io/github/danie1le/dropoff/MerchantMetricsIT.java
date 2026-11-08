package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * merchant-service on {@code /actuator/prometheus} (M6-1, ticket #34): request latency with histogram buckets, and the
 * dead-letter counter every service shares. It has no gauges or counters of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MerchantMetricsIT {

    private final MockMvcTester mvc;

    MerchantMetricsIT(@Autowired MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void theScrapeShowsRequestLatencyAndDeadLetters() throws Exception {
        assertThat(mvc.get().uri("/api/v1/merchants/{id}", Long.MAX_VALUE).exchange())
                .hasStatus4xxClientError();

        MvcTestResult result = mvc.get().uri("/actuator/prometheus").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getContentAsString())
                .contains("http_server_requests_seconds_bucket{")
                .contains("dropoff_dlt_messages_total{topic=\"merchants\"}");
    }
}
