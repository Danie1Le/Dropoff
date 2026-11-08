package io.github.danie1le.dropoff.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.Consumers;
import io.github.danie1le.dropoff.KafkaTestSupport;
import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.common.KafkaTopics;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * merchant-service sends every merchant and menu item change to its compacted topic through the outbox, keyed by id, so
 * order-service can keep its copies with this service stopped (M5-5, ADR-0007). Commits its rows, so it deletes them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MerchantEventsIT {

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final List<Long> merchantIds = new ArrayList<>();

    MerchantEventsIT(@Autowired MockMvcTester mvc, @Autowired JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @AfterEach
    void deleteRows() {
        Consumers.awaitSettled(jdbc);
        if (!merchantIds.isEmpty()) {
            jdbc.sql("DELETE FROM menu_items WHERE merchant_id IN (:ids)")
                    .param("ids", merchantIds)
                    .update();
            jdbc.sql("DELETE FROM merchants WHERE id IN (:ids)")
                    .param("ids", merchantIds)
                    .update();
        }
    }

    private long post(String uri, String json) {
        MvcTestResult result = mvc.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotNull();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    void sendsMerchantAndMenuItemChangesToTheirTopicsKeyedById() {
        String name = "Taco Shop " + UUID.randomUUID();
        long merchantId = post("/api/v1/merchants", """
                {"name":"%s","address":"100 Congress Ave","lat":30.2672,"lng":-97.7431}
                """.formatted(name));
        merchantIds.add(merchantId);
        long itemId = post("/api/v1/merchants/" + merchantId + "/menu-items", """
                {"name":"%s","priceCents":399}
                """.formatted(name));

        ConsumerRecord<String, byte[]> merchant = KafkaTestSupport.readUntil(
                        KafkaTopics.MERCHANTS, r -> KafkaTestSupport.text(r).contains(name))
                .getLast();
        assertThat(merchant.key()).isEqualTo(Long.toString(merchantId));
        assertThat(KafkaTestSupport.header(merchant, "__TypeId__")).isEqualTo("MerchantChanged");
        assertThat(KafkaTestSupport.text(merchant)).contains("\"version\":1", "\"open\":true");

        ConsumerRecord<String, byte[]> item = KafkaTestSupport.readUntil(
                        KafkaTopics.MENU_ITEMS, r -> KafkaTestSupport.text(r).contains(name))
                .getLast();
        assertThat(item.key()).isEqualTo(Long.toString(itemId));
        assertThat(KafkaTestSupport.header(item, "__TypeId__")).isEqualTo("MenuItemChanged");
        assertThat(KafkaTestSupport.text(item)).contains("\"priceCents\":399", "\"merchantId\":" + merchantId);
    }
}
