package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.danie1le.dropoff.DispatchTestcontainers;
import io.github.danie1le.dropoff.contracts.OrderReady;
import io.github.danie1le.dropoff.dispatch.DispatchFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dispatch end to end, against real Postgres and Redis (M3-2, decisions #11, #13, #14, ticket #42). The Redis writes
 * run after commit and the races need real commits, so nothing is rolled back: each test remembers its orders and
 * couriers, and deletes them and takes the couriers out of the geo set afterwards.
 */
@SpringBootTest
@Import(DispatchTestcontainers.class)
class DispatchIT {

    private final DispatchService dispatch;
    private final DispatchAgainJob job;
    private final CourierService couriers;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();

    DispatchIT(
            @Autowired DispatchService dispatch,
            @Autowired DispatchAgainJob job,
            @Autowired CourierService couriers,
            @Autowired StringRedisTemplate redis,
            @Autowired JdbcClient jdbc,
            @Autowired TransactionTemplate transaction,
            @Autowired JsonMapper json) {
        this.dispatch = dispatch;
        this.job = job;
        this.couriers = couriers;
        this.redis = redis;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
    }

    @AfterEach
    void deleteCreatedRows() {
        if (!courierIds.isEmpty()) {
            redis.opsForGeo()
                    .remove(
                            CourierGeoIndex.KEY,
                            courierIds.stream().map(String::valueOf).toArray(String[]::new));
        }
        DispatchFixtures.deleteOrders(jdbc, orderIds);
        deleteByIds("couriers", courierIds);
        orderIds.clear();
        courierIds.clear();
    }

    private void deleteByIds(String table, List<Long> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("DELETE FROM " + table + " WHERE id IN (:ids)")
                    .param("ids", ids)
                    .update();
        }
    }

    /** A READY order already in {@code dispatch_orders} as WAITING, as the listener leaves it. */
    private long waitingOrder() {
        long orderId = DispatchFixtures.waitingOrder(jdbc);
        orderIds.add(orderId);
        return orderId;
    }

    private long courierAt(double lat, double lng) {
        long id = couriers.create("Carla").id();
        courierIds.add(id);
        couriers.moveTo(id, new Position(lat, lng));
        return id;
    }

    private long availableCourierAt(double lat, double lng) {
        long id = courierAt(lat, lng);
        couriers.changeStatus(id, CourierStatus.AVAILABLE);
        return id;
    }

    private String courierStatus(long courierId) {
        return jdbc.sql("SELECT status FROM couriers WHERE id = ?")
                .param(courierId)
                .query(String.class)
                .single();
    }

    private boolean inGeoSet(long courierId) {
        return redis.opsForZSet().score(CourierGeoIndex.KEY, Long.toString(courierId)) != null;
    }

    /** The couriers with an OFFERED offer for the order. */
    private List<Long> openOffers(long orderId) {
        return jdbc.sql("SELECT courier_id FROM offers WHERE order_id = ? AND status = 'OFFERED'")
                .param(orderId)
                .query(Long.class)
                .list();
    }

    private String dispatchStatus(long orderId) {
        return jdbc.sql("SELECT status FROM dispatch_orders WHERE order_id = ?")
                .param(orderId)
                .query(String.class)
                .single();
    }

    @Test
    void anOrderReadyEventIsOfferedToTheNearestCourier() {
        long far = availableCourierAt(30.2849, -97.7341);
        long near = availableCourierAt(30.2680, -97.7431);
        long orderId = DispatchFixtures.orderId();
        orderIds.add(orderId);
        OrderReady ready = DispatchFixtures.ready(orderId);

        DispatchFixtures.sendOrderEvent(json, orderId, ready);

        await().atMost(Duration.ofSeconds(10)).until(() -> !openOffers(orderId).isEmpty());
        assertThat(openOffers(orderId)).containsExactly(near);
        assertThat(dispatchStatus(orderId)).isEqualTo("WAITING");
        Instant[] times = jdbc.sql("SELECT offered_at, expires_at FROM offers WHERE order_id = ?")
                .param(orderId)
                .query((rs, row) -> new Instant[] {
                    rs.getTimestamp("offered_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant()
                })
                .single();
        assertThat(Duration.between(times[0], times[1])).isEqualTo(Duration.ofSeconds(30));
        assertThat(courierStatus(near)).isEqualTo("BUSY");
        await().atMost(Duration.ofSeconds(5)).until(() -> !inGeoSet(near));
        assertThat(courierStatus(far)).isEqualTo("AVAILABLE");
        assertThat(inGeoSet(far)).isTrue();
        // The consumer committed this row with the WAITING row, before the offer; the test removes it like its rows.
        jdbc.sql("DELETE FROM processed_events WHERE event_id = ?")
                .param(ready.eventId())
                .update();
    }

    @Test
    void leavesTheOrderWaitingWhenNoCourierIsWithinFiveKilometres() {
        long outOfRange = availableCourierAt(30.40, -97.74);
        long orderId = waitingOrder();

        assertThat(dispatch.dispatch(orderId)).isFalse();

        assertThat(openOffers(orderId)).isEmpty();
        assertThat(dispatchStatus(orderId)).isEqualTo("WAITING");
        assertThat(courierStatus(outOfRange)).isEqualTo("AVAILABLE");
    }

    @Test
    void skipsAndRemovesAStaleGeoMemberWhoseClaimFails() {
        long next = availableCourierAt(30.2849, -97.7341);
        long stale = courierAt(30.2680, -97.7431);
        // A failed or reordered after-commit ZREM can leave an OFFLINE courier in the set; the test plants one, nearer.
        redis.opsForGeo().add(CourierGeoIndex.KEY, new Point(-97.7431, 30.2680), Long.toString(stale));
        long orderId = waitingOrder();

        assertThat(dispatch.dispatch(orderId)).isTrue();

        assertThat(openOffers(orderId)).containsExactly(next);
        assertThat(courierStatus(stale)).isEqualTo("OFFLINE");
        assertThat(inGeoSet(stale)).isFalse();
    }

    @Test
    void skipsACourierOfferedThisOrderInTheLastFiveMinutes() {
        long declined = availableCourierAt(30.2680, -97.7431);
        long other = availableCourierAt(30.2849, -97.7341);
        long orderId = waitingOrder();
        jdbc.sql("INSERT INTO offers (order_id, courier_id, status, offered_at, expires_at, responded_at)"
                        + " VALUES (?, ?, 'DECLINED', now() - interval '1 minute', now() - interval '30 seconds',"
                        + " now() - interval '50 seconds')")
                .params(orderId, declined)
                .update();

        assertThat(dispatch.dispatch(orderId)).isTrue();

        assertThat(openOffers(orderId)).containsExactly(other);
        assertThat(courierStatus(declined)).isEqualTo("AVAILABLE");
    }

    @Test
    void doesNotDispatchAnOrderThatAlreadyHasAnOpenOffer() {
        long first = availableCourierAt(30.2680, -97.7431);
        long second = availableCourierAt(30.2849, -97.7341);
        long orderId = waitingOrder();
        assertThat(dispatch.dispatch(orderId)).isTrue();

        assertThat(dispatch.dispatch(orderId)).isFalse();

        assertThat(openOffers(orderId)).containsExactly(first);
        assertThat(courierStatus(second)).isEqualTo("AVAILABLE");
    }

    /** Both orders want the only courier. One gets an offer; the other's claim finds them BUSY and it stays WAITING. */
    @Test
    void twoOrdersRacingForOneCourierGiveItToOnlyOne() throws Exception {
        long courier = availableCourierAt(30.2680, -97.7431);
        long a = waitingOrder();
        long b = waitingOrder();

        List<Boolean> results = race(List.of(() -> dispatch.dispatch(a), () -> dispatch.dispatch(b)));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        List<Long> offered = new ArrayList<>(openOffers(a));
        offered.addAll(openOffers(b));
        assertThat(offered).containsExactly(courier);
        assertThat(courierStatus(courier)).isEqualTo("BUSY");
        assertThat(inGeoSet(courier)).isFalse();
        assertThat(dispatchStatus(a)).isEqualTo("WAITING");
        assertThat(dispatchStatus(b)).isEqualTo("WAITING");
    }

    /**
     * The listener and the job dispatch the same order at once (decision #14). Only one offer is sent and only one
     * courier is claimed; the others stay AVAILABLE and in the geo set.
     */
    @Test
    void theListenerAndTheJobRacingSendOneOffer() throws Exception {
        List<Long> fleet = List.of(
                availableCourierAt(30.2680, -97.7431),
                availableCourierAt(30.2700, -97.7431),
                availableCourierAt(30.2849, -97.7341));
        long orderId = waitingOrder();

        race(List.of(
                () -> dispatch.dispatch(orderId),
                () -> {
                    job.run();
                    return true;
                },
                () -> dispatch.dispatch(orderId),
                () -> {
                    job.run();
                    return true;
                }));

        assertThat(openOffers(orderId)).hasSize(1);
        long offered = openOffers(orderId).get(0);
        for (long courier : fleet) {
            boolean isOffered = courier == offered;
            assertThat(courierStatus(courier)).isEqualTo(isOffered ? "BUSY" : "AVAILABLE");
            assertThat(inGeoSet(courier)).isEqualTo(!isOffered);
        }
    }

    /** The index behind that race: a second open offer fails, and rolling back undoes its courier claim. */
    @Test
    void aSecondOpenOfferIsRejectedAndItsClaimRolledBack() {
        long first = availableCourierAt(30.2680, -97.7431);
        long second = availableCourierAt(30.2849, -97.7341);
        long orderId = waitingOrder();
        assertThat(dispatch.dispatch(orderId)).isTrue();

        assertThatThrownBy(
                        () -> transaction.executeWithoutResult(tx -> dispatch.tryOffer(orderId, second, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("one_open_offer_per_order");

        assertThat(openOffers(orderId)).containsExactly(first);
        assertThat(courierStatus(second)).isEqualTo("AVAILABLE");
        assertThat(inGeoSet(second)).isTrue();
    }

    @Test
    void theJobDispatchesAWaitingOrderOnceACourierComesIntoRange() {
        long courier = courierAt(30.2680, -97.7431);
        long orderId = waitingOrder();

        job.run();
        assertThat(openOffers(orderId)).isEmpty();

        couriers.changeStatus(courier, CourierStatus.AVAILABLE);
        job.run();

        assertThat(openOffers(orderId)).containsExactly(courier);
        assertThat(courierStatus(courier)).isEqualTo("BUSY");
    }

    @Test
    void theJobLeavesOrdersWithAnOpenOfferAlone() {
        long first = availableCourierAt(30.2680, -97.7431);
        long second = availableCourierAt(30.2849, -97.7341);
        long orderId = waitingOrder();
        assertThat(dispatch.dispatch(orderId)).isTrue();

        job.run();

        assertThat(openOffers(orderId)).containsExactly(first);
        assertThat(courierStatus(second)).isEqualTo("AVAILABLE");
    }

    /** Runs the tasks on their own threads, released at the same moment, and returns their results in order. */
    private static List<Boolean> race(List<Callable<Boolean>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
