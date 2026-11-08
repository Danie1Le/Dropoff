package io.github.danie1le.dropoff;

import io.github.danie1le.dropoff.common.KafkaTopics;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The app's Kafka consumer groups and the topics each reads, for {@link KafkaTestSupport#awaitSettled}. */
public final class Consumers {

    /** Dispatch's {@code dispatch-service} group left with dispatch-service (M5-4). */
    public static final Map<String, List<String>> GROUPS = Map.of(
            "order-service",
            List.of(
                    KafkaTopics.COURIER_EVENTS,
                    KafkaTopics.COURIER_LOCATIONS,
                    KafkaTopics.MERCHANTS,
                    KafkaTopics.MENU_ITEMS));

    private Consumers() {}

    /** Waits until every event the app sent is out and every event it reads is consumed. */
    public static void awaitSettled(JdbcClient jdbc) {
        KafkaTestSupport.awaitSettled(jdbc, GROUPS);
    }
}
