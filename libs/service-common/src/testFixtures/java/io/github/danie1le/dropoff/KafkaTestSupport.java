package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration.SharedKafka;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads, writes and waits on the shared test broker without going through the service. */
public final class KafkaTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private KafkaTestSupport() {}

    /**
     * Waits until every event has gone out and been consumed: no publication left in the registry, and every consumer
     * group has committed up to the end of its topics. A consumer can publish new events, so the check passes only when
     * the end offsets did not move while the registry was read. Run it before deleting rows a consumer may still touch.
     *
     * @param consumed the topics each consumer group of the service under test reads, by group id
     */
    public static void awaitSettled(JdbcClient jdbc, Map<String, List<String>> consumed) {
        try (Admin admin =
                Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, SharedKafka.bootstrapServers()))) {
            Awaitility.await().pollDelay(Duration.ZERO).atMost(WAIT).until(() -> {
                Map<TopicPartition, Long> before = endOffsetsIfConsumed(admin, consumed);
                if (before == null || pendingPublications(jdbc) > 0) {
                    return false;
                }
                return before.equals(endOffsetsIfConsumed(admin, consumed));
            });
        }
    }

    private static long pendingPublications(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL"
                        + " AND status IS DISTINCT FROM 'FAILED'")
                .query(Long.class)
                .single();
    }

    /** The end offsets of all consumed topics, or null while some group still lags behind them. */
    private static Map<TopicPartition, Long> endOffsetsIfConsumed(Admin admin, Map<String, List<String>> groups)
            throws ExecutionException, InterruptedException {
        Map<TopicPartition, Long> ends = new HashMap<>();
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group.getKey())
                    .partitionsToOffsetAndMetadata()
                    .get();
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (String topic : group.getValue()) {
                for (int partition = 0; partition < 3; partition++) {
                    latest.put(new TopicPartition(topic, partition), OffsetSpec.latest());
                }
            }
            for (var end : admin.listOffsets(latest).all().get().entrySet()) {
                OffsetAndMetadata done = committed.get(end.getKey());
                long consumed = done == null ? 0 : done.offset();
                if (consumed < end.getValue().offset()) {
                    return null;
                }
                ends.put(end.getKey(), end.getValue().offset());
            }
        }
        return ends;
    }

    /** Sends a raw record, as another service or a broken producer would. */
    public static void send(String topic, String key, String typeId, byte[] value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedKafka.bootstrapServers());
        try (KafkaProducer<String, byte[]> producer =
                new KafkaProducer<>(props, new StringSerializer(), new ByteArraySerializer())) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
            record.headers().add("__TypeId__", typeId.getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads the topic from the start until a record matches, and returns every record read up to and including it.
     * Fails if none matches in time.
     */
    public static List<ConsumerRecord<String, byte[]>> readUntil(
            String topic, Predicate<ConsumerRecord<String, byte[]>> match) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedKafka.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, byte[]>> read = new java.util.ArrayList<>();
        Instant deadline = Instant.now().plus(WAIT);
        try (KafkaConsumer<String, byte[]> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    read.add(record);
                    if (match.test(record)) {
                        return read;
                    }
                }
            }
        }
        assertThat(read).as("a matching record on %s", topic).anyMatch(match);
        return read;
    }

    /**
     * Reads the topic from the start up to its current end and returns every record, oldest first per partition. Use it
     * to check a record did not go out, after {@link #awaitSettled} says nothing more is on its way.
     */
    public static List<ConsumerRecord<String, byte[]>> readAll(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedKafka.bootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, byte[]>> read = new java.util.ArrayList<>();
        Instant deadline = Instant.now().plus(WAIT);
        try (KafkaConsumer<String, byte[]> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            while (partitions.stream().anyMatch(p -> consumer.position(p) < ends.get(p))) {
                assertThat(Instant.now())
                        .as("read %s to its end in time", topic)
                        .isBefore(deadline);
                consumer.poll(Duration.ofMillis(200)).forEach(read::add);
            }
        }
        return read;
    }

    public static String text(ConsumerRecord<String, byte[]> record) {
        return new String(record.value(), StandardCharsets.UTF_8);
    }

    public static String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }
}
