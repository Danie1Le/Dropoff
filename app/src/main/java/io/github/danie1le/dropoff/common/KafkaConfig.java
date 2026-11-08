package io.github.danie1le.dropoff.common;

import io.github.danie1le.dropoff.contracts.CourierAssigned;
import io.github.danie1le.dropoff.contracts.CourierLocationUpdated;
import io.github.danie1le.dropoff.contracts.CourierUnassigned;
import io.github.danie1le.dropoff.contracts.MenuItemChanged;
import io.github.danie1le.dropoff.contracts.MerchantChanged;
import io.github.danie1le.dropoff.contracts.OrderCancelled;
import io.github.danie1le.dropoff.contracts.OrderDelivered;
import io.github.danie1le.dropoff.contracts.OrderPickedUp;
import io.github.danie1le.dropoff.contracts.OrderPlaced;
import io.github.danie1le.dropoff.contracts.OrderReady;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.mapping.DefaultJacksonJavaTypeMapper;
import org.springframework.kafka.support.mapping.JacksonJavaTypeMapper.TypePrecedence;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.util.backoff.BackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka inside the app (M5-1, tickets #15, #32, #42, ADR-0006).
 *
 * <ul>
 *   <li>Topics are declared here and created at startup; the broker never creates one on its own.
 *   <li>Events leave through the Modulith event registry (the outbox): it stores each event in the transaction that
 *       publishes it, and sends it to its topic only after that transaction commits.
 *   <li>Values are JSON. The {@code __TypeId__} header carries a logical name ({@code OrderReady}) instead of the Java
 *       class, so the classes can move packages, and consumers pick the type from it.
 *   <li>A failed record is retried 3 times (1 s, 2 s, 4 s), then sent to {@code <topic>.DLT}. A record that cannot be
 *       read at all goes there at once.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    static final int PARTITIONS = 3;
    static final Duration DLT_RETENTION = Duration.ofDays(14);
    static final String DLT_SUFFIX = ".DLT";

    /**
     * Every event that crosses Kafka. Its simple name is its logical type name. Add each new contract event here, and
     * route it in {@link #eventExternalization} if the registry sends it. A type missing from this list has no logical name, so its records fail conversion and go
     * straight to the DLT.
     */
    static final List<Class<? extends Record>> EVENTS = List.of(
            OrderPlaced.class,
            OrderCancelled.class,
            OrderReady.class,
            OrderPickedUp.class,
            OrderDelivered.class,
            CourierAssigned.class,
            CourierUnassigned.class,
            CourierLocationUpdated.class,
            MerchantChanged.class,
            MenuItemChanged.class);

    @Bean
    KafkaAdmin.NewTopics topics() {
        List<NewTopic> topics = new ArrayList<>();
        topics.add(deleting(KafkaTopics.ORDER_EVENTS, Duration.ofDays(7)));
        topics.add(deleting(KafkaTopics.COURIER_EVENTS, Duration.ofDays(7)));
        topics.add(deleting(KafkaTopics.COURIER_LOCATIONS, Duration.ofHours(1)));
        topics.add(TopicBuilder.name(KafkaTopics.MERCHANTS)
                .partitions(PARTITIONS)
                .replicas(1)
                .compact()
                .build());
        topics.add(TopicBuilder.name(KafkaTopics.MENU_ITEMS)
                .partitions(PARTITIONS)
                .replicas(1)
                .compact()
                .build());
        Stream.of(
                        KafkaTopics.ORDER_EVENTS,
                        KafkaTopics.COURIER_EVENTS,
                        KafkaTopics.COURIER_LOCATIONS,
                        KafkaTopics.MERCHANTS,
                        KafkaTopics.MENU_ITEMS)
                .map(topic -> deleting(deadLetterTopic(topic), DLT_RETENTION))
                .forEach(topics::add);
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    private static NewTopic deleting(String name, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(PARTITIONS)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retention.toMillis()))
                .build();
    }

    /**
     * Which events the registry sends to Kafka, and where. Order and courier events are keyed by orderId; merchant and
     * menu item changes by their own id, since a compacted topic keeps only the latest record per key (ticket #16).
     * {@code CourierLocationUpdated} is not among them: dispatch
     * sends it straight with {@code KafkaTemplate} (ticket #15). The routes live here, not in {@code @Externalized} on
     * the records, because the contracts package carries no Spring (ADR-0008).
     */
    @Bean
    EventExternalizationConfiguration eventExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .selectByType(type -> EVENTS.contains(type) && type != CourierLocationUpdated.class)
                .route(OrderPlaced.class, e -> to(KafkaTopics.ORDER_EVENTS, e.orderId()))
                .route(OrderCancelled.class, e -> to(KafkaTopics.ORDER_EVENTS, e.orderId()))
                .route(OrderReady.class, e -> to(KafkaTopics.ORDER_EVENTS, e.orderId()))
                .route(OrderPickedUp.class, e -> to(KafkaTopics.ORDER_EVENTS, e.orderId()))
                .route(OrderDelivered.class, e -> to(KafkaTopics.ORDER_EVENTS, e.orderId()))
                .route(CourierAssigned.class, e -> to(KafkaTopics.COURIER_EVENTS, e.orderId()))
                .route(CourierUnassigned.class, e -> to(KafkaTopics.COURIER_EVENTS, e.orderId()))
                .route(MerchantChanged.class, e -> to(KafkaTopics.MERCHANTS, e.merchantId()))
                .route(MenuItemChanged.class, e -> to(KafkaTopics.MENU_ITEMS, e.menuItemId()))
                .build();
    }

    private static RoutingTarget to(String topic, long key) {
        return RoutingTarget.forTarget(topic).andKey(Long.toString(key));
    }

    /**
     * JSON in and out, typed by the logical name in {@code __TypeId__}. Used by the template (the registry's relay,
     * {@code CourierLocationUpdated}) and by every listener.
     */
    @Bean
    RecordMessageConverter kafkaMessageConverter(JsonMapper jsonMapper) {
        DefaultJacksonJavaTypeMapper typeMapper = new DefaultJacksonJavaTypeMapper();
        typeMapper.setIdClassMapping(typeNames());
        typeMapper.setTypePrecedence(TypePrecedence.TYPE_ID);
        ByteArrayJacksonJsonMessageConverter converter = new ByteArrayJacksonJsonMessageConverter(jsonMapper);
        converter.setTypeMapper(typeMapper);
        return converter;
    }

    /**
     * Retries, then dead-letters to {@code <topic>.DLT} on the record's partition (ticket #32). Spring Kafka's own
     * default suffix is {@code -dlt}, so the name is set here.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, ex) -> new TopicPartition(deadLetterTopic(record.topic()), record.partition()));
        return new DefaultErrorHandler(recoverer, retryBackOff());
    }

    static String deadLetterTopic(String topic) {
        return topic + DLT_SUFFIX;
    }

    /** 3 redeliveries, 1 s, 2 s and 4 s apart (ticket #32). */
    static BackOff retryBackOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2);
        return backOff;
    }

    /** Logical type name to class, for both directions of the {@code __TypeId__} header. */
    static Map<String, Class<?>> typeNames() {
        return EVENTS.stream().collect(Collectors.toMap(Class::getSimpleName, Function.<Class<?>>identity()));
    }
}
