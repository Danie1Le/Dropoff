package io.github.danie1le.dropoff.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The system clock, injected wherever code reads the current time so tests can swap it. */
@Configuration(proxyBeanMethods = false)
class SchedulingConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
