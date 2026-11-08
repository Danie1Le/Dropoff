package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.order.OrderFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Offer endpoints end to end, against real Postgres and Redis (M3-3, ticket #11). Accepting publishes {@code
 * CourierAssigned}, whose order listener runs after commit, so nothing is rolled back: each test remembers its rows,
 * deletes them afterwards and takes its couriers out of the geo set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OfferApiIT {

    private static final double SHOP_LAT = 30.2672;

    private static final double SHOP_LNG = -97.7431;

    private final DispatchService dispatch;
    private final CourierService couriers;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final MockMvcTester mvc;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();
    private final List<Long> customerIds = new ArrayList<>();
    private final List<Long> merchantIds = new ArrayList<>();

    OfferApiIT(
            @Autowired DispatchService dispatch,
            @Autowired CourierService couriers,
            @Autowired StringRedisTemplate redis,
            @Autowired JdbcClient jdbc,
            @Autowired MockMvcTester mvc) {
        this.dispatch = dispatch;
        this.couriers = couriers;
        this.redis = redis;
        this.jdbc = jdbc;
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
        if (!orderIds.isEmpty()) {
            for (String table : List.of("offers", "dispatch_orders", "order_events", "order_lines")) {
                jdbc.sql("DELETE FROM " + table + " WHERE order_id IN (:ids)")
                        .param("ids", orderIds)
                        .update();
            }
            jdbc.sql("DELETE FROM orders WHERE id IN (:ids)")
                    .param("ids", orderIds)
                    .update();
        }
        deleteByIds("couriers", courierIds);
        deleteByIds("customers", customerIds);
        deleteByIds("merchants", merchantIds);
        for (List<Long> ids : List.of(orderIds, courierIds, customerIds, merchantIds)) {
            ids.clear();
        }
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
        long customerId = OrderFixtures.customer(jdbc);
        long merchantId = OrderFixtures.merchant(jdbc, true);
        customerIds.add(customerId);
        merchantIds.add(merchantId);
        long orderId = OrderFixtures.order(
                jdbc, customerId, merchantId, "READY", 399, Instant.now().toString());
        orderIds.add(orderId);
        jdbc.sql("INSERT INTO dispatch_orders (order_id, merchant_name, merchant_address, pickup_lat, pickup_lng,"
                        + " delivery_address, delivery_lat, delivery_lng, ready_at)"
                        + " VALUES (?, 'Taco Shop', '100 Congress Ave', ?, ?, '200 Lavaca St', 30.2669, -97.7466,"
                        + " now())")
                .params(orderId, SHOP_LAT, SHOP_LNG)
                .update();
        return orderId;
    }

    private long availableCourierAt(double lat, double lng) {
        long id = couriers.create("Carla").id();
        courierIds.add(id);
        couriers.moveTo(id, new Position(lat, lng));
        couriers.changeStatus(id, CourierStatus.AVAILABLE);
        return id;
    }

    /** An order offered to a courier standing at the shop. */
    private Offered offered() {
        long courierId = availableCourierAt(30.2680, -97.7431);
        long orderId = waitingOrder();
        assertThat(dispatch.dispatch(orderId)).isTrue();
        return new Offered(courierId, orderId, offerOf(orderId, courierId));
    }

    private record Offered(long courierId, long orderId, long offerId) {}

    private long offerOf(long orderId, long courierId) {
        return jdbc.sql("SELECT id FROM offers WHERE order_id = ? AND courier_id = ?")
                .params(orderId, courierId)
                .query(Long.class)
                .single();
    }

    private MvcTestResult command(long courierId, long offerId, String command) {
        return mvc.post()
                .uri("/api/v1/couriers/{c}/offers/{o}/{command}", courierId, offerId, command)
                .exchange();
    }

    private String value(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    /** The order's courier, or null. Read with {@code list()}, since {@code single()} rejects a null value. */
    private @Nullable Long orderCourier(long orderId) {
        return jdbc.sql("SELECT courier_id FROM orders WHERE id = ?")
                .param(orderId)
                .query((rs, row) -> rs.getObject("courier_id", Long.class))
                .list()
                .get(0);
    }

    private boolean inGeoSet(long courierId) {
        return redis.opsForZSet().score(CourierGeoIndex.KEY, Long.toString(courierId)) != null;
    }

    @Test
    void theCurrentOfferShowsPickupAndDelivery() {
        Offered o = offered();

        MvcTestResult result = mvc.get()
                .uri("/api/v1/couriers/{c}/offers/current", o.courierId())
                .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.offerId").isEqualTo((int) o.offerId());
        assertThat(result).bodyJson().extractingPath("$.orderId").isEqualTo((int) o.orderId());
        assertThat(result).bodyJson().extractingPath("$.merchantName").isEqualTo("Taco Shop");
        assertThat(result).bodyJson().extractingPath("$.merchantAddress").isEqualTo("100 Congress Ave");
        assertThat(result).bodyJson().extractingPath("$.pickupLat").isEqualTo(SHOP_LAT);
        assertThat(result).bodyJson().extractingPath("$.pickupLng").isEqualTo(SHOP_LNG);
        assertThat(result).bodyJson().extractingPath("$.deliveryAddress").isEqualTo("200 Lavaca St");
        assertThat(result).bodyJson().extractingPath("$.deliveryLat").isEqualTo(30.2669);
        assertThat(result).bodyJson().extractingPath("$.deliveryLng").isEqualTo(-97.7466);
        assertThat(result).bodyJson().extractingPath("$.expiresAt").isNotNull();
    }

    @Test
    void noOpenOfferIs204AndAnUnknownCourierIs404() {
        long courierId = availableCourierAt(30.2680, -97.7431);

        assertThat(mvc.get()
                        .uri("/api/v1/couriers/{c}/offers/current", courierId)
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get()
                        .uri("/api/v1/couriers/{c}/offers/current", Long.MAX_VALUE)
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("COURIER_NOT_FOUND");
    }

    @Test
    void acceptingAssignsTheOrderAndRecordsTheCourierOnIt() {
        Offered o = offered();

        MvcTestResult result = command(o.courierId(), o.offerId(), "accept");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.offerId").isEqualTo((int) o.offerId());
        assertThat(result).bodyJson().extractingPath("$.orderId").isEqualTo((int) o.orderId());
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("ACCEPTED");
        assertThat(result).bodyJson().extractingPath("$.respondedAt").isNotNull();
        assertThat(value("SELECT status FROM dispatch_orders WHERE order_id = ?", o.orderId()))
                .isEqualTo("ASSIGNED");
        assertThat(value("SELECT courier_id::text FROM dispatch_orders WHERE order_id = ?", o.orderId()))
                .isEqualTo(Long.toString(o.courierId()));
        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.courierId()))
                .isEqualTo("BUSY");
        await().atMost(Duration.ofSeconds(10)).until(() -> orderCourier(o.orderId()) != null);
        assertThat(orderCourier(o.orderId())).isEqualTo(o.courierId());
        assertThat(mvc.get()
                        .uri("/api/v1/couriers/{c}/offers/current", o.courierId())
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void aRepeatedAcceptIsANoOp() throws Exception {
        Offered o = offered();
        MvcTestResult first = command(o.courierId(), o.offerId(), "accept");
        assertThat(first).hasStatusOk();
        String respondedAt = value("SELECT responded_at::text FROM offers WHERE id = ?", o.offerId());

        MvcTestResult repeat = command(o.courierId(), o.offerId(), "accept");

        assertThat(repeat).hasStatusOk();
        assertThat(repeat).bodyJson().extractingPath("$.status").isEqualTo("ACCEPTED");
        assertThat(repeat.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(value("SELECT responded_at::text FROM offers WHERE id = ?", o.offerId()))
                .isEqualTo(respondedAt);
    }

    @Test
    void aLateAcceptIsClosed() {
        Offered o = offered();
        expire(o.offerId());

        assertThat(command(o.courierId(), o.offerId(), "accept"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("OFFER_CLOSED");
        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("OFFERED");
        assertThat(value("SELECT status FROM dispatch_orders WHERE order_id = ?", o.orderId()))
                .isEqualTo("WAITING");
    }

    private void expire(long offerId) {
        jdbc.sql("UPDATE offers SET offered_at = now() - interval '31 seconds', expires_at = now() - interval"
                        + " '1 millisecond' WHERE id = ?")
                .param(offerId)
                .update();
    }

    @Test
    void anExpiredOfferThatIsStillOfferedIsNotCurrent() {
        Offered o = offered();
        expire(o.offerId());

        assertThat(mvc.get()
                        .uri("/api/v1/couriers/{c}/offers/current", o.courierId())
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("OFFERED");
    }

    @Test
    void aLateDeclineIsClosedAndChangesNothing() {
        Offered o = offered();
        availableCourierAt(30.2849, -97.7341);
        expire(o.offerId());

        assertThat(command(o.courierId(), o.offerId(), "decline"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("OFFER_CLOSED");
        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("OFFERED");
        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.courierId()))
                .isEqualTo("BUSY");
        assertThat(value("SELECT count(*)::text FROM offers WHERE order_id = ?", o.orderId()))
                .isEqualTo("1");
    }

    @Test
    void aDeclineAfterGoingOfflineIsARepeatAndTheCourierStaysOffline() {
        Offered o = offered();
        // Going OFFLINE already declined the open offer (M3-4, ticket #85).
        couriers.changeStatus(o.courierId(), CourierStatus.OFFLINE);

        assertThat(command(o.courierId(), o.offerId(), "decline"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("DECLINED");
        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.courierId()))
                .isEqualTo("OFFLINE");
        assertThat(inGeoSet(o.courierId())).isFalse();
    }

    @Test
    void anAcceptAfterGoingOfflineIsClosed() {
        Offered o = offered();
        couriers.changeStatus(o.courierId(), CourierStatus.OFFLINE);

        assertThat(command(o.courierId(), o.offerId(), "accept"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("OFFER_CLOSED");
        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("DECLINED");
        assertThat(value("SELECT status FROM dispatch_orders WHERE order_id = ?", o.orderId()))
                .isEqualTo("WAITING");
        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.courierId()))
                .isEqualTo("OFFLINE");
    }

    @Test
    void anotherCouriersOfferIsNotFound() {
        Offered o = offered();
        long other = availableCourierAt(30.2849, -97.7341);

        for (String command : List.of("accept", "decline")) {
            assertThat(command(other, o.offerId(), command))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("OFFER_NOT_FOUND");
        }
        assertThat(command(o.courierId(), Long.MAX_VALUE, "accept"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("OFFER_NOT_FOUND");
        assertThat(command(Long.MAX_VALUE, o.offerId(), "accept"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("COURIER_NOT_FOUND");
        assertThat(value("SELECT status FROM offers WHERE id = ?", o.offerId())).isEqualTo("OFFERED");
    }

    @Test
    void decliningFreesTheCourierAndOffersTheOrderToTheNextOne() {
        Offered o = offered();
        long next = availableCourierAt(30.2849, -97.7341);

        MvcTestResult result = command(o.courierId(), o.offerId(), "decline");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("DECLINED");
        assertThat(result).bodyJson().extractingPath("$.respondedAt").isNotNull();
        assertThat(value("SELECT status FROM couriers WHERE id = ?", o.courierId()))
                .isEqualTo("AVAILABLE");
        await().atMost(Duration.ofSeconds(5)).until(() -> inGeoSet(o.courierId()));
        assertThat(value("SELECT courier_id::text FROM offers WHERE order_id = ? AND status = 'OFFERED'", o.orderId()))
                .isEqualTo(Long.toString(next));
        assertThat(value("SELECT status FROM dispatch_orders WHERE order_id = ?", o.orderId()))
                .isEqualTo("WAITING");
    }

    @Test
    void aRepeatedDeclineIsANoOpAndALaterAcceptIsClosed() {
        Offered declined = offered();
        assertThat(command(declined.courierId(), declined.offerId(), "decline")).hasStatusOk();

        assertThat(command(declined.courierId(), declined.offerId(), "decline"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("DECLINED");
        assertThat(command(declined.courierId(), declined.offerId(), "accept"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("OFFER_CLOSED");
    }

    @Test
    void decliningAnAcceptedOfferIsClosed() {
        Offered accepted = offered();
        assertThat(command(accepted.courierId(), accepted.offerId(), "accept")).hasStatusOk();
        assertThat(command(accepted.courierId(), accepted.offerId(), "decline"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("OFFER_CLOSED");
        assertThat(value("SELECT status FROM couriers WHERE id = ?", accepted.courierId()))
                .isEqualTo("BUSY");
    }
}
