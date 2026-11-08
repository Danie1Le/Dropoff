package io.github.danie1le.dropoff;

import io.github.danie1le.dropoff.common.KafkaTopics;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** dispatch-service's Kafka consumer group and the topics it reads, for {@link KafkaTestSupport#awaitSettled}. */
public final class Consumers {

    public static final Map<String, List<String>> GROUPS =
            Map.of("dispatch-service", List.of(KafkaTopics.ORDER_EVENTS));

    private Consumers() {}

    /** Waits until every event the service sent is out and every event it reads is consumed. */
    public static void awaitSettled(JdbcClient jdbc) {
        KafkaTestSupport.awaitSettled(jdbc, GROUPS);
    }
}
