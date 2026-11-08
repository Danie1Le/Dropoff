package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Redis geo writes wait for the Postgres commit and are skipped on rollback (ticket #42, second gate pass). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CourierGeoIndexIT {

    private final CourierService service;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transaction;

    CourierGeoIndexIT(
            @Autowired CourierService service,
            @Autowired StringRedisTemplate redis,
            @Autowired TransactionTemplate transaction) {
        this.service = service;
        this.redis = redis;
        this.transaction = transaction;
    }

    private long courierWithLocation() {
        long id = service.create("Carla").id();
        service.moveTo(id, new Position(30.2672, -97.7431));
        return id;
    }

    private boolean inGeoSet(long id) {
        return redis.opsForZSet().score(CourierGeoIndex.KEY, Long.toString(id)) != null;
    }

    @Test
    void addsTheCourierOnlyOnceTheTransactionCommits() {
        long id = courierWithLocation();

        transaction.executeWithoutResult(status -> {
            service.changeStatus(id, CourierStatus.AVAILABLE);
            assertThat(inGeoSet(id)).as("in the geo set before commit").isFalse();
        });

        assertThat(inGeoSet(id)).isTrue();
    }

    @Test
    void leavesRedisUntouchedWhenTheTransactionRollsBack() {
        long id = courierWithLocation();

        transaction.executeWithoutResult(status -> {
            service.changeStatus(id, CourierStatus.AVAILABLE);
            status.setRollbackOnly();
        });

        assertThat(inGeoSet(id)).isFalse();
        assertThat(service.get(id).status()).isEqualTo(CourierStatus.OFFLINE);
    }
}
