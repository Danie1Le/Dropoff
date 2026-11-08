package io.github.danie1le.dropoff;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts a real Postgres and Kafka for integration tests and points the service at them. Shared by every service's ITs
 * (M5-4); dispatch-service adds Redis on top.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        // Same image as compose.yaml; keep the two in sync.
        return new PostgreSQLContainer("postgres:18.6-alpine");
    }

    /**
     * Points every test context at the one shared broker. Not a container bean: Spring would stop it when the first
     * context closes, while other contexts still use it.
     */
    @Bean
    DynamicPropertyRegistrar kafkaProperties() {
        return registry -> registry.add("spring.kafka.bootstrap-servers", SharedKafka::bootstrapServers);
    }

    /**
     * One Kafka broker for the whole test run, started on first use and removed by Testcontainers when the JVM exits.
     * Starting a broker per context would cost seconds each. Spring pauses the contexts not in use, which stops their
     * Kafka listeners, so only the running test's context consumes.
     */
    public static final class SharedKafka {

        // Same image and settings as compose.yaml; keep the two in sync.
        private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");

        static {
            KAFKA.start();
        }

        private SharedKafka() {}

        public static String bootstrapServers() {
            return KAFKA.getBootstrapServers();
        }
    }
}
