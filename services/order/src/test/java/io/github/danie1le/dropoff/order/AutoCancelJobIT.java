package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.contracts.Actor;
import io.github.danie1le.dropoff.contracts.OrderCancelled;
import io.github.danie1le.dropoff.order.OrderCommandApiIT.Fixture;
import io.github.danie1le.dropoff.order.internal.AutoCancelJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

/** PLACED orders older than the auto-cancel limit become CANCELLED by SYSTEM (M2-7, decisions #13, #14). */
@SpringBootTest
@AutoConfigureMockMvc
@EnableScenarios
@Import({TestcontainersConfiguration.class, AutoCancelJobIT.ClockFixture.class, AutoCancelJobIT.FailingOrder.class})
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

    /**
     * Makes cancelling one chosen order fail: a synchronous listener throws inside the cancel transaction, so that
     * order's cancel rolls back.
     */
    static class FailingOrder {
        private volatile long orderId = -1;

        void set(long id) {
            orderId = id;
        }

        @EventListener
        void on(OrderCancelled event) {
            if (event.orderId() == orderId) {
                throw new IllegalStateException("cancel of order " + orderId + " fails");
            }
        }
    }

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final AutoCancelJob job;
    private final MutableClock clock;
    private final TransactionTemplate transactions;
    private final FailingOrder failing;

    AutoCancelJobIT(
            @Autowired MockMvcTester mvc,
            @Autowired JdbcClient jdbc,
            @Autowired AutoCancelJob job,
            @Autowired MutableClock clock,
            @Autowired TransactionTemplate transactions,
            @Autowired FailingOrder failing) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.job = job;
        this.clock = clock;
        this.transactions = transactions;
        this.failing = failing;
    }

    @BeforeEach
    void resetClock() {
        clock.set(Instant.now());
    }

    @AfterEach
    void cleanUp() {
        failing.set(-1);
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

    /** The test config (config/application.yml) turns scheduling off, so only these tests drive {@code run()}. */
    @Test
    void schedulingIsOffInTests(@Autowired ApplicationContext context) {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .isEmpty();
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
                    assertThat(e.actor()).isEqualTo(Actor.SYSTEM);
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

    /**
     * Another transaction holds the row lock on the oldest order while the job runs. With {@code SKIP LOCKED} the job
     * skips it and cancels the others at once; without it the job would block on that row and time out here.
     */
    @Test
    void runSkipsAnOrderLockedByAnotherRun() throws Exception {
        Fixture locked = OrderCommandApiIT.placed(mvc, jdbc);
        Fixture b = OrderCommandApiIT.placed(mvc, jdbc);
        Fixture c = OrderCommandApiIT.placed(mvc, jdbc);
        clock.advance(Duration.ofMinutes(11));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            jdbc.sql("SELECT id FROM orders WHERE id = ? FOR UPDATE")
                    .param(locked.orderId())
                    .query(Long.class)
                    .single();
            lockHeld.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
        try {
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture.runAsync(job::run).get(10, TimeUnit.SECONDS);
            assertThat(status(locked.orderId())).isEqualTo("PLACED");
            assertThat(status(b.orderId())).isEqualTo("CANCELLED");
            assertThat(status(c.orderId())).isEqualTo("CANCELLED");
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        job.run();
        for (Fixture f : new Fixture[] {locked, b, c}) {
            assertThat(status(f.orderId())).isEqualTo("CANCELLED");
            assertThat(systemCancels(f.orderId())).isEqualTo(1);
        }
    }

    /** Two runs at once over the same stale orders: every order is cancelled, and none is cancelled twice. */
    @Test
    void concurrentRunsNeverCancelAnOrderTwice() throws Exception {
        List<Fixture> stale = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            stale.add(OrderCommandApiIT.placed(mvc, jdbc));
        }
        clock.advance(Duration.ofMinutes(11));
        CountDownLatch start = new CountDownLatch(1);
        Runnable run = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            job.run();
        };
        CompletableFuture<Void> first = CompletableFuture.runAsync(run);
        CompletableFuture<Void> second = CompletableFuture.runAsync(run);
        start.countDown();
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
        for (Fixture f : stale) {
            assertThat(status(f.orderId())).isEqualTo("CANCELLED");
            assertThat(systemCancels(f.orderId())).isEqualTo(1);
        }
    }

    /** A cancel that fails for the oldest order does not stop the run: it steps past it and cancels the rest. */
    @Test
    void oneFailingOrderDoesNotStallTheRun() {
        Fixture bad = OrderCommandApiIT.placed(mvc, jdbc);
        clock.advance(Duration.ofMinutes(1));
        Fixture b = OrderCommandApiIT.placed(mvc, jdbc);
        Fixture c = OrderCommandApiIT.placed(mvc, jdbc);
        clock.advance(Duration.ofMinutes(11));
        failing.set(bad.orderId());

        job.run();

        assertThat(status(bad.orderId())).isEqualTo("PLACED");
        assertThat(systemCancels(bad.orderId())).isZero();
        assertThat(status(b.orderId())).isEqualTo("CANCELLED");
        assertThat(status(c.orderId())).isEqualTo("CANCELLED");
    }
}
