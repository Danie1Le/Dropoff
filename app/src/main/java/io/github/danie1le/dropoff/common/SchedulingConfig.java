package io.github.danie1le.dropoff.common;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The system clock for jobs, and {@code @Scheduled} jobs on unless {@code dropoff.scheduling.enabled=false}. */
@Configuration(proxyBeanMethods = false)
class SchedulingConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty(name = "dropoff.scheduling.enabled", matchIfMissing = true)
    static class Enabled {}
}
