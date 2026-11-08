package io.github.danie1le.dropoff;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** merchant-service's Kafka consumer groups, for {@link KafkaTestSupport#awaitSettled}. It only produces, so none. */
public final class Consumers {

    public static final Map<String, List<String>> GROUPS = Map.of();

    private Consumers() {}

    /** Waits until every event the service sent is out. */
    public static void awaitSettled(JdbcClient jdbc) {
        KafkaTestSupport.awaitSettled(jdbc, GROUPS);
    }
}
