package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.order.OrderCommandApiIT.Fixture;
import io.github.danie1le.dropoff.order.internal.AutoCancelJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** PLACED orders older than the auto-cancel limit become CANCELLED by SYSTEM (M2-7, decisions #13, #14). */
@SpringBootTest(properties = "dropoff.scheduling.enabled=false")
@AutoConfigureMockMvc
@EnableScenarios
@Import({TestcontainersConfiguration.class, AutoCancelJobIT.ClockFixture.class})
class AutoCancelJobIT {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockFixture {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final AutoCancelJob job;
    private final MutableClock clock;

    AutoCancelJobIT(
            @Autowired MockMvcTester mvc,
            @Autowired JdbcClient jdbc,
            @Autowired AutoCancelJob job,
            @Autowired MutableClock clock) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.job = job;
        this.clock = clock;
    }

    @BeforeEach
    void resetClock() {
        clock.set(Instant.now());
    }

    @AfterEach
    void cleanUp() {
        OrderCommandApiIT.deleteAll(jdbc);
    }

    private String status(long orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = ?")
                .param(orderId)
                .query(String.class)
                .single();
    }

    private int systemCancels(long orderId) {
        return jdbc.sql("SELECT count(*) FROM order_events WHERE order_id = ? AND to_status = 'CANCELLED'"
                        + " AND actor = 'SYSTEM'")
                .param(orderId)
                .query(Integer.class)
                .single();
    }

    @Test
    void leavesYoungPlacedOrdersAlone() {
        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        clock.advance(Duration.ofMinutes(9));
        job.run();
        assertThat(status(f.orderId())).isEqualTo("PLACED");
    }

    @Test
    void cancelsPlacedOrdersOlderThanTheLimit(Scenario scenario) {
        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        clock.advance(Duration.ofMinutes(11));
        scenario.stimulate(job::run)
                .andWaitForEventOfType(OrderCancelled.class)
                .matching(e -> e.orderId() == f.orderId())
                .toArriveAndVerify(e -> {
                    assertThat(e.actor()).isEqualTo("SYSTEM");
                    assertThat(e.merchantId()).isEqualTo(f.merchantId());
                });
        assertThat(status(f.orderId())).isEqualTo("CANCELLED");
        assertThat(systemCancels(f.orderId())).isEqualTo(1);
    }

    @Test
    void leavesAcceptedOrdersAlone() {
        Fixture f = OrderCommandApiIT.placed(mvc, jdbc);
        assertThat(mvc.post()
                        .uri("/api/v1/merchants/{id}/orders/{o}/accept", f.merchantId(), f.orderId())
                        .exchange())
                .hasStatusOk();
        clock.advance(Duration.ofMinutes(11));
        job.run();
        assertThat(status(f.orderId())).isEqualTo("ACCEPTED");
    }

    @Test
    void twoConcurrentRunsNeverCancelTwice() throws Exception {
        Fixture a = OrderCommandApiIT.placed(mvc, jdbc);
        Fixture b = OrderCommandApiIT.placed(mvc, jdbc);
        Fixture c = OrderCommandApiIT.placed(mvc, jdbc);
        clock.advance(Duration.ofMinutes(11));
        CyclicBarrier start = new CyclicBarrier(2);
        Runnable run = () -> {
            try {
                start.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            job.run();
        };
        CompletableFuture.allOf(CompletableFuture.runAsync(run), CompletableFuture.runAsync(run))
                .get();
        for (Fixture f : new Fixture[] {a, b, c}) {
            assertThat(status(f.orderId())).isEqualTo("CANCELLED");
            assertThat(systemCancels(f.orderId())).isEqualTo(1);
        }
    }
}
