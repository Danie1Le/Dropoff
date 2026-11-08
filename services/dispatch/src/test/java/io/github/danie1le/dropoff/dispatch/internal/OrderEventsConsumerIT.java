package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.Consumers;
import io.github.danie1le.dropoff.DispatchTestcontainers;
import io.github.danie1le.dropoff.KafkaTestSupport;
import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.contracts.OrderReady;
import io.github.danie1le.dropoff.dispatch.DispatchFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * dispatch-service reads {@code order-events} as the {@code dispatch-service} group (M5-1, M5-4): it dedupes a
 * redelivered event and dead-letters a record it cannot read, with the shared Kafka setup from {@code
 * libs/service-common}. No courier is AVAILABLE in these tests, so a dispatch finds nobody and sends no offer.
 */
@SpringBootTest
@Import(DispatchTestcontainers.class)
class OrderEventsConsumerIT {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

    OrderEventsConsumerIT(@Autowired JdbcClient jdbc, @Autowired JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @AfterEach
    void deleteCreatedRows() {
        DispatchFixtures.deleteOrders(jdbc, orderIds);
        if (!eventIds.isEmpty()) {
            jdbc.sql("DELETE FROM processed_events WHERE event_id IN (:ids)")
                    .param("ids", eventIds)
                    .update();
        }
        orderIds.clear();
        eventIds.clear();
    }

    @Test
    void aRedeliveredOrderReadyOpensTheOrderOnce() {
        long orderId = DispatchFixtures.orderId();
        orderIds.add(orderId);
        OrderReady ready = DispatchFixtures.ready(orderId);
        eventIds.add(ready.eventId());

        DispatchFixtures.sendOrderEvent(json, orderId, ready);
        DispatchFixtures.sendOrderEvent(json, orderId, ready);
        Consumers.awaitSettled(jdbc);

        assertThat(jdbc.sql("SELECT status FROM dispatch_orders WHERE order_id = ?")
                        .param(orderId)
                        .query(String.class)
                        .list())
                .containsExactly("WAITING");
        assertThat(jdbc.sql("SELECT count(*) FROM processed_events WHERE consumer_group = 'dispatch-service'"
                                + " AND event_id = ?")
                        .param(ready.eventId())
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void aPoisonMessageEndsInTheDeadLetterTopic() {
        String key = Long.toString(DispatchFixtures.orderId());
        byte[] poison = "not json".getBytes(StandardCharsets.UTF_8);

        KafkaTestSupport.send(KafkaTopics.ORDER_EVENTS, key, "OrderReady", poison);

        ConsumerRecord<String, byte[]> dead = KafkaTestSupport.readUntil(
                        KafkaTopics.ORDER_EVENTS + ".DLT", record -> key.equals(record.key()))
                .getLast();
        assertThat(dead.value()).isEqualTo(poison);
        assertThat(KafkaTestSupport.header(dead, "kafka_dlt-original-consumer-group"))
                .isEqualTo("dispatch-service");
    }
}
