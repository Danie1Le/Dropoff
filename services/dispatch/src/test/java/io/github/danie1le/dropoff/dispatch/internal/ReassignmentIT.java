package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.danie1le.dropoff.DispatchTestcontainers;
import io.github.danie1le.dropoff.dispatch.DispatchFixtures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Timeouts, going OFFLINE and reassignment end to end, against real Postgres, Redis and Kafka (M3-4, ticket #85).
 * {@code CourierAssigned} and {@code CourierUnassigned} leave through the outbox after commit, so nothing is rolled
 * back: each test remembers its rows, deletes them afterwards and takes its couriers out of the geo set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DispatchTestcontainers.class)
class ReassignmentIT {

    private final DispatchService dispatch;
    private final OfferTimeoutJob job;
    private final CourierService couriers;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final MockMvcTester mvc;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();

    ReassignmentIT(
            @Autowired DispatchService dispatch,
            @Autowired OfferTimeoutJob job,
            @Autowired CourierService couriers,
            @Autowired StringRedisTemplate redis,
            @Autowired JdbcClient jdbc,
            @Autowired TransactionTemplate transaction,
            @Autowired MockMvcTester mvc) {
        this.dispatch = dispatch;
        this.job = job;
        this.couriers = couriers;
        this.redis = redis;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mvc = mvc;
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

    /** A READY order in {@code dispatch_orders} as WAITING, as the {@code OrderReady} listener leaves it. */
    private long waitingOrder() {
        long orderId = DispatchFixtures.waitingOrder(jdbc);
        orderIds.add(orderId);
        return orderId;
    }

    private long availableCourierAt(double lat, double lng) {
        long id = couriers.create("Carla").id();
        courierIds.add(id);
        couriers.moveTo(id, new Position(lat, lng));
        couriers.changeStatus(id, CourierStatus.AVAILABLE);
        return id;
    }

    /** An order offered to a courier at the shop, with a second courier further away waiting for the next offer. */
    private Offered offeredWithANextCourier() {
        long courierId = availableCourierAt(30.2680, -97.7431);
        long next = availableCourierAt(30.2849, -97.7341);
        long orderId = waitingOrder();
        assertThat(dispatch.dispatch(orderId)).isTrue();
        return new Offered(courierId, orderId, offerOf(orderId, courierId), next);
    }

    private record Offered(long courierId, long orderId, long offerId, long next) {}

    private long offerOf(long orderId, long courierId) {
        return jdbc.sql("SELECT id FROM offers WHERE order_id = ? AND courier_id = ?")
                .params(orderId, courierId)
                .query(Long.class)
                .single();
    }

    private void expire(long offerId) {
        jdbc.sql("UPDATE offers SET offered_at = now() - interval '31 seconds', expires_at = now() - interval"
                        + " '1 millisecond' WHERE id = ?")
                .param(offerId)
                .update();
    }

    private MvcTestResult goOffline(long courierId) {
        return mvc.patch()
                .uri("/api/v1/couriers/{id}/status", courierId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"OFFLINE"}
                        """)
                .exchange();
    }

    private MvcTestResult accept(Offered o) {
        return mvc.post()
                .uri("/api/v1/couriers/{c}/offers/{o}/accept", o.courierId(), o.offerId())
                .exchange();
    }

    private String value(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    /** A nullable column read with {@code list()}, since {@code single()} rejects a null value. */
    private @Nullable Long nullableLong(String sql, long id) {
        return jdbc.sql(sql)
                .param(id)
                .query((rs, row) -> rs.getObject(1, Long.class))
                .list()
                .get(0);
    }

    /** The courier holding the order's open offer. */
    private String openOfferCourier(long orderId) {
        return value("SELECT courier_id::text FROM offers WHERE order_id = ? AND status = 'OFFERED'", orderId);
    }

    private boolean inGeoSet(long courierId) {
        return redis.opsForZSet().score(CourierGeoIndex.KEY, Long.toString(courierId)) != null;
    }

    @Test
    void theTimeoutJobTimesOutAnExpiredOfferAndOffersTheOrderToTheNextCourier() {
        Offered o = offeredWithANextCourier();
        expire(o.offerId());

        job.run();

        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("TIMED_OUT");
        assertThat(nullableLong(
                        "SELECT extract(epoch FROM responded_at)::bigint FROM offers WHERE id = ?", o.offerId()))
                .isNotNull();
        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.courierId()))
                .isEqualTo("OFFLINE");
        assertThat(inGeoSet(o.courierId())).isFalse();
        assertThat(openOfferCourier(o.orderId())).isEqualTo(Long.toString(o.next()));
        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.next())).isEqualTo("BUSY");
    }

    @Test
    void theTimeoutJobLeavesAnOfferThatHasNotExpired() {
        Offered o = offeredWithANextCourier();

        job.run();

        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("OFFERED");
        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.courierId()))
                .isEqualTo("BUSY");
    }

    @Test
    void theTimeoutJobSkipsAnOfferWhoseCourierIsLocked() throws Exception {
        Offered o = offeredWithANextCourier();
        expire(o.offerId());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Holds the courier lock the way a decline or accept does, so the job must skip the row, not wait on it.
            Future<?> holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("SELECT id FROM couriers WHERE id = ? FOR UPDATE")
                        .param(o.courierId())
                        .query(Long.class)
                        .single();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            job.run();

            assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId()))
                    .isEqualTo("OFFERED");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        job.run();

        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("TIMED_OUT");
    }

    @Test
    void goingOfflineDeclinesTheOpenOfferAndOffersTheOrderToTheNextCourier() {
        Offered o = offeredWithANextCourier();

        assertThat(goOffline(o.courierId()))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("OFFLINE");

        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("DECLINED");
        assertThat(inGeoSet(o.courierId())).isFalse();
        assertThat(openOfferCourier(o.orderId())).isEqualTo(Long.toString(o.next()));
    }

    @Test
    void goingOfflineAfterAcceptingUnassignsTheOrderAndDispatchesItAgain() {
        Offered o = offeredWithANextCourier();
        assertThat(accept(o)).hasStatusOk();
        DispatchFixtures.awaitCourierEvent("CourierAssigned", o.orderId(), o.courierId());

        assertThat(goOffline(o.courierId()))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("OFFLINE");

        assertThat(value("SELECT status FROM dispatch_orders WHERE order_id = ?", o.orderId()))
                .isEqualTo("WAITING");
        assertThat(nullableLong("SELECT courier_id FROM dispatch_orders WHERE order_id = ?", o.orderId()))
                .isNull();
        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("ACCEPTED");
        assertThat(openOfferCourier(o.orderId())).isEqualTo(Long.toString(o.next()));
        // order-service clears the courier from this event (its CourierAssignmentWriterIT), only for this offer.
        assertThat(DispatchFixtures.awaitCourierEvent("CourierUnassigned", o.orderId(), o.courierId()))
                .contains("\"offerId\":" + o.offerId() + ",");
    }

    @Test
    void goingOfflineWithAPickedUpOrderIsAConflictAndChangesNothing() {
        Offered o = offeredWithANextCourier();
        assertThat(accept(o)).hasStatusOk();
        // Pick-up lands in M3-5 (#86); until then the test moves the dispatch copy on directly.
        jdbc.sql("UPDATE dispatch_orders SET status = 'PICKED_UP' WHERE order_id = ?")
                .param(o.orderId())
                .update();

        assertThat(goOffline(o.courierId()))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("COURIER_HAS_ACTIVE_DELIVERY");

        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.courierId()))
                .isEqualTo("BUSY");
        assertThat(value("SELECT status FROM dispatch_orders WHERE order_id = ?", o.orderId()))
                .isEqualTo("PICKED_UP");
    }

    @Test
    void goingOfflineWaitsForAPickupInFlightAndThenRefuses() throws Exception {
        Offered o = offeredWithANextCourier();
        assertThat(accept(o)).hasStatusOk();
        CountDownLatch pickedUp = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Dispatch's pickup, not committed yet: it holds the dispatch_orders row lock.
            Future<?> pickup = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("UPDATE dispatch_orders SET status = 'PICKED_UP' WHERE order_id = ?")
                        .param(o.orderId())
                        .update();
                pickedUp.countDown();
                try {
                    finish.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(pickedUp.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MvcTestResult> offline = executor.submit(() -> goOffline(o.courierId()));
            await().atMost(Duration.ofSeconds(5)).until(this::someoneWaitsOnDispatchOrders);

            finish.countDown();
            pickup.get(10, TimeUnit.SECONDS);

            assertThat(offline.get(10, TimeUnit.SECONDS))
                    .hasStatus(HttpStatus.CONFLICT)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("COURIER_HAS_ACTIVE_DELIVERY");
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.courierId()))
                .isEqualTo("BUSY");
        assertThat(value("SELECT status FROM dispatch_orders WHERE order_id = ?", o.orderId()))
                .isEqualTo("PICKED_UP");
    }

    /** Whether a session is blocked on a row lock in a statement on {@code dispatch_orders}. */
    private boolean someoneWaitsOnDispatchOrders() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND query LIKE '%dispatch_orders%' AND pid <> pg_backend_pid())")
                .query(Boolean.class)
                .single();
    }

    @Test
    void goingOfflineTimesOutAnOfferThatHasAlreadyExpired() {
        Offered o = offeredWithANextCourier();
        expire(o.offerId());

        assertThat(goOffline(o.courierId())).hasStatusOk();

        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("TIMED_OUT");
    }
}
