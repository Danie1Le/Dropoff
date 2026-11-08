package io.github.danie1le.dropoff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * order-service (M5-6, ADR-0007): customers, orders and the live WebSocket. It sits in the root package, not in
 * {@code order}, so component scan also finds the shared {@code web} and {@code common} configs from {@code libs/}.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
