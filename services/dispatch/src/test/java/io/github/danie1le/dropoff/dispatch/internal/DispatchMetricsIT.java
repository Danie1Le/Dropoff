package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.DispatchTestcontainers;
import io.github.danie1le.dropoff.dispatch.DispatchFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * dispatch-service's metrics on {@code /actuator/prometheus} (M6-1, tickets #13, #34, #42). Not rolled back: the
 * counters move only after commit, so each test deletes its rows and takes its couriers out of the geo set. Meters are
 * shared by every test in the context, so each test compares against the value it read first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DispatchTestcontainers.class)
class DispatchMetricsIT {

    private final DispatchService dispatch;
    private final CourierService couriers;
    private final OfferService offers;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final MockMvcTester mvc;
    private final MeterRegistry meters;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();

    DispatchMetricsIT(
            @Autowired DispatchService dispatch,
            @Autowired CourierService couriers,
            @Autowired OfferService offers,
            @Autowired StringRedisTemplate redis,
            @Autowired JdbcClient jdbc,
            @Autowired MockMvcTester mvc,
            @Autowired MeterRegistry meters) {
        this.dispatch = dispatch;
        this.couriers = couriers;
        this.offers = offers;
        this.redis = redis;
        this.jdbc = jdbc;
        this.mvc = mvc;
        this.meters = meters;
    }

    @AfterEach
    void deleteCreatedRows() {
        DispatchFixtures.deleteOrders(jdbc, orderIds);
        if (!courierIds.isEmpty()) {
            redis.opsForGeo()
                    .remove(
                            CourierGeoIndex.KEY,
                            courierIds.stream().map(String::valueOf).toArray(String[]::new));
            jdbc.sql("DELETE FROM couriers WHERE id IN (:ids)")
                    .param("ids", courierIds)
                    .update();
        }
        orderIds.clear();
        courierIds.clear();
    }

    /** A WAITING order whose {@code ready_at} is {@code secondsAgo} in the past. */
    private long waitingOrder(int secondsAgo) {
        long orderId = DispatchFixtures.waitingOrder(jdbc);
        orderIds.add(orderId);
        jdbc.sql("UPDATE dispatch_orders SET ready_at = now() - make_interval(secs => ?) WHERE order_id = ?")
                .params(secondsAgo, orderId)
                .update();
        return orderId;
    }

    private long availableCourier() {
        long id = couriers.create("Carla").id();
        courierIds.add(id);
        couriers.moveTo(id, new Position(30.2680, -97.7431));
        couriers.changeStatus(id, CourierStatus.AVAILABLE);
        return id;
    }

    private record Offered(long courierId, long orderId, long offerId) {}

    /** An order READY {@code secondsAgo}, offered to a courier standing at the shop. */
    private Offered offered(int secondsAgo) {
        long courierId = availableCourier();
        long orderId = waitingOrder(secondsAgo);
        assertThat(dispatch.dispatch(orderId)).isTrue();
        long offerId = jdbc.sql("SELECT id FROM offers WHERE order_id = ? AND courier_id = ?")
                .params(orderId, courierId)
                .query(Long.class)
                .single();
        return new Offered(courierId, orderId, offerId);
    }

    private MvcTestResult command(Offered o, String command) {
        return mvc.post()
                .uri("/api/v1/couriers/{c}/offers/{o}/{command}", o.courierId(), o.offerId(), command)
                .exchange();
    }

    private double offersEnded(String outcome) {
        return meters.get("dropoff.offers").tag("outcome", outcome).counter().count();
    }

    private Timer dispatchDuration() {
        return meters.get("dropoff.dispatch.duration").timer();
    }

    private double gauge(String name, String... tags) {
        return meters.get(name).tags(tags).gauge().value();
    }

    @Test
    void theScrapeShowsEveryDispatchMetric() throws Exception {
        MvcTestResult result = mvc.get().uri("/actuator/prometheus").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getContentAsString())
                .contains("dropoff_offers_total{outcome=\"ACCEPTED\"}")
                .contains("dropoff_offers_total{outcome=\"DECLINED\"}")
                .contains("dropoff_offers_total{outcome=\"TIMED_OUT\"}")
                .contains("dropoff_dispatch_duration_seconds_bucket{")
                .contains("dropoff_orders_waiting_for_courier ")
                .contains("dropoff_oldest_waiting_order_age_seconds ")
                .contains("dropoff_couriers{status=\"OFFLINE\"}")
                .contains("dropoff_couriers{status=\"AVAILABLE\"}")
                .contains("dropoff_couriers{status=\"BUSY\"}")
                .contains("dropoff_dlt_messages_total{topic=\"order-events\"}");
    }

    @Test
    void acceptingCountsTheOfferAndTimesTheWaitFromReady() {
        Offered o = offered(60);
        double accepted = offersEnded("ACCEPTED");
        long timed = dispatchDuration().count();
        double timedSeconds = dispatchDuration().totalTime(TimeUnit.SECONDS);

        assertThat(command(o, "accept")).hasStatusOk();

        assertThat(offersEnded("ACCEPTED")).isEqualTo(accepted + 1);
        assertThat(dispatchDuration().count()).isEqualTo(timed + 1);
        // READY 60 s before the accept.
        assertThat(dispatchDuration().totalTime(TimeUnit.SECONDS) - timedSeconds)
                .isBetween(60.0, 120.0);
    }

    @Test
    void aRepeatedAcceptIsCountedOnce() {
        Offered o = offered(0);
        assertThat(command(o, "accept")).hasStatusOk();
        double accepted = offersEnded("ACCEPTED");

        assertThat(command(o, "accept")).hasStatusOk();

        assertThat(offersEnded("ACCEPTED")).isEqualTo(accepted);
    }

    @Test
    void decliningCountsTheOffer() {
        Offered o = offered(0);
        double declined = offersEnded("DECLINED");

        assertThat(command(o, "decline")).hasStatusOk();

        assertThat(offersEnded("DECLINED")).isEqualTo(declined + 1);
    }

    @Test
    void goingOfflineWithAnOpenOfferCountsItAsDeclined() {
        Offered o = offered(0);
        double declined = offersEnded("DECLINED");

        couriers.changeStatus(o.courierId(), CourierStatus.OFFLINE);

        assertThat(offersEnded("DECLINED")).isEqualTo(declined + 1);
    }

    @Test
    void anOfferNobodyAnswersCountsAsTimedOut() {
        Offered o = offered(0);
        jdbc.sql(
                        "UPDATE offers SET offered_at = now() - interval '31 seconds', expires_at = now() - interval '1 second'"
                                + " WHERE id = ?")
                .param(o.offerId())
                .update();
        double timedOut = offersEnded("TIMED_OUT");

        assertThat(offers.timeOutNextExpired(new HashSet<>())).isTrue();

        assertThat(offersEnded("TIMED_OUT")).isEqualTo(timedOut + 1);
    }

    @Test
    void theGaugesCountWaitingOrdersAndCouriersByStatus() {
        double waiting = gauge("dropoff.orders.waiting.for.courier");
        double available = gauge("dropoff.couriers", "status", "AVAILABLE");

        waitingOrder(120);
        availableCourier();

        assertThat(gauge("dropoff.orders.waiting.for.courier")).isEqualTo(waiting + 1);
        assertThat(gauge("dropoff.oldest.waiting.order.age")).isGreaterThanOrEqualTo(120.0);
        assertThat(gauge("dropoff.couriers", "status", "AVAILABLE")).isEqualTo(available + 1);
    }
}
