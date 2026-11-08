package io.github.danie1le.dropoff;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;

/** The shared Postgres and Kafka setup, plus the Redis only dispatch-service uses (its geo index and throttle). */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestcontainersConfiguration.class)
public class DispatchTestcontainers {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        // Same image as compose.yaml; keep the two in sync.
        return new GenericContainer<>("redis:8.10.3-alpine").withExposedPorts(6379);
    }
}
