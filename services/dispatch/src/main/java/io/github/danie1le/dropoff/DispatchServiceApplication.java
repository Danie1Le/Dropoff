package io.github.danie1le.dropoff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * dispatch-service (M5-4, ADR-0007). It sits in the root package, not in {@code dispatch}, so component scan also
 * finds the shared {@code web} and {@code common} configs from {@code libs/}.
 */
@SpringBootApplication
public class DispatchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatchServiceApplication.class, args);
    }
}
