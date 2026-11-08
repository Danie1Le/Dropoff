package io.github.danie1le.dropoff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * merchant-service (M5-5, ADR-0007). It sits in the root package, not in {@code merchant}, so component scan also
 * finds the shared {@code web} and {@code common} configs from {@code libs/}.
 */
@SpringBootApplication
public class MerchantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantServiceApplication.class, args);
    }
}
