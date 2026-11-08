package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.contracts.Actor;
import io.github.danie1le.dropoff.contracts.OrderCancelled;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Events reach Kafka through the outbox, and consumers dedupe and dead-letter (M5-1, tickets #15, #32). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KafkaEventsIT {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final List<UUID> eventIds = new java.util.ArrayList<>();

    KafkaEventsIT(
            @Autowired JdbcClient jdbc,
            @Autowired ApplicationEventPublisher events,
            @Autowired TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.events = events;
        this.tx = tx;
    }

    @AfterEach
    void deleteProcessedEvents() {
        KafkaTestSupport.awaitSettled(jdbc);
        if (!eventIds.isEmpty()) {
            jdbc.sql("DELETE FROM processed_events WHERE event_id IN (:ids)")
                    .param("ids", eventIds)
                    .update();
        }
    }

    /** An order id no test row uses, so the consumers' guarded updates match nothing. */
    private static long unknownOrderId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private static OrderCancelled cancelled(long orderId) {
        return new OrderCancelled(
                UUID.randomUUID(), orderId, 1, Actor.SYSTEM, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void anEventIsSentAfterCommitAndNotAfterRollback() {
        long orderId = unknownOrderId();
        OrderCancelled rolledBack = cancelled(orderId);
        OrderCancelled committed = cancelled(orderId);

        tx.executeWithoutResult(status -> {
            events.publishEvent(rolledBack);
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> events.publishEvent(committed));

        // Same key, same partition: had the rolled-back event gone out, it would come before the committed one.
        List<ConsumerRecord<String, byte[]>> read = KafkaTestSupport.readUntil(
                KafkaTopics.ORDER_EVENTS,
                record -> KafkaTestSupport.text(record)
                        .contains(committed.eventId().toString()));
        ConsumerRecord<String, byte[]> sent = read.getLast();
        assertThat(sent.key()).isEqualTo(Long.toString(orderId));
        assertThat(KafkaTestSupport.header(sent, "__TypeId__")).isEqualTo("OrderCancelled");
        assertThat(KafkaTestSupport.text(sent)).contains("\"actor\":\"SYSTEM\"");
        assertThat(read)
                .noneMatch(record -> KafkaTestSupport.text(record)
                        .contains(rolledBack.eventId().toString()));
    }

    /**
     * The handler logs a skip for the unknown order each time it runs, and {@code ProcessedEvents} logs a skip each time
     * it drops a duplicate, so each line must appear exactly once. Had the duplicate run the handler again, the first
     * would appear twice and the second not at all.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aDuplicateIsProcessedOnce(CapturedOutput output) {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        long orderId = unknownOrderId();
        byte[] unassigned = ("{\"eventId\":\"%s\",\"orderId\":%d,\"courierId\":1,\"offerId\":1,"
                        + "\"occurredAt\":\"2026-11-08T12:00:00Z\"}")
                .formatted(eventId, orderId)
                .getBytes(StandardCharsets.UTF_8);

        KafkaTestSupport.send(KafkaTopics.COURIER_EVENTS, Long.toString(orderId), "CourierUnassigned", unassigned);
        KafkaTestSupport.send(KafkaTopics.COURIER_EVENTS, Long.toString(orderId), "CourierUnassigned", unassigned);
        KafkaTestSupport.awaitSettled(jdbc);

        assertThat(jdbc.sql("SELECT count(*) FROM processed_events WHERE consumer_group = 'order-service'"
                                + " AND event_id = ?")
                        .param(eventId)
                        .query(Long.class)
                        .single())
                .isOne();
        assertThat(occurrences(output, "Skipped CourierUnassigned " + eventId))
                .as("handler runs")
                .isOne();
        assertThat(occurrences(output, "Skipped event " + eventId + ": consumer group order-service"))
                .as("duplicates skipped")
                .isOne();
    }

    private static long occurrences(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).count();
    }

    @Test
    void aPoisonMessageEndsInTheDeadLetterTopic() {
        String key = Long.toString(unknownOrderId());
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
