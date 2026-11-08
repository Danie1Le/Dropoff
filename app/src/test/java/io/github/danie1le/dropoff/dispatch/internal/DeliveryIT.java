package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.danie1le.dropoff.KafkaTestSupport;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Pickup and delivery on dispatch's side (M3-5), against real Postgres and Redis. The guarded updates are tested by
 * calling {@link DeliveryService} directly; the last test runs the whole path through the API and the event listeners.
 * Nothing is rolled back, so each test deletes its rows and takes its couriers out of the geo set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeliveryIT {

    private final DispatchService dispatch;
    private final DeliveryService delivery;
    private final CourierService couriers;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final MockMvcTester mvc;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();
    private final List<Long> customerIds = new ArrayList<>();
    private final List<Long> merchantIds = new ArrayList<>();

    DeliveryIT(
            @Autowired DispatchService dispatch,
            @Autowired DeliveryService delivery,
            @Autowired CourierService couriers,
            @Autowired StringRedisTemplate redis,
            @Autowired JdbcClient jdbc,
            @Autowired MockMvcTester mvc) {
        this.dispatch = dispatch;
        this.delivery = delivery;
        this.couriers = couriers;
        this.redis = redis;
        this.jdbc = jdbc;
        this.mvc = mvc;
    }

    @AfterEach
    void deleteCreatedRows() {
        // The API test's events reach their Kafka consumers asynchronously; let them finish before deleting their rows.
        KafkaTestSupport.awaitSettled(jdbc);
        if (!courierIds.isEmpty()) {
            redis.opsForGeo()
                    .remove(
                            CourierGeoIndex.KEY,
                            courierIds.stream().map(String::valueOf).toArray(String[]::new));
        }
        OrderFixtures.deleteOrders(jdbc, orderIds);
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

    /** A READY order offered to a courier standing at the shop. */
    private Offered offered() {
        long courierId = couriers.create("Carla").id();
        courierIds.add(courierId);
        couriers.moveTo(courierId, new Position(30.2680, -97.7431));
        couriers.changeStatus(courierId, CourierStatus.AVAILABLE);
        return offeredTo(courierId);
    }

    private record Offered(long courierId, long orderId, long offerId) {}

    /** An order the courier has accepted: ASSIGNED in {@code dispatch_orders}, with the courier BUSY. */
    private Offered assigned() {
        Offered o = offered();
        assertThat(post("/api/v1/couriers/{c}/offers/{o}/accept", o.courierId(), o.offerId()))
                .hasStatusOk();
        return o;
    }

    private MvcTestResult post(String uri, long courierId, long id) {
        return mvc.post().uri(uri, courierId, id).exchange();
    }

    private String dispatchStatus(long orderId) {
        return value("SELECT status FROM dispatch_orders WHERE order_id = ?", orderId);
    }

    private String courierStatus(long courierId) {
        return value("SELECT status FROM couriers WHERE id = ?", courierId);
    }

    private String value(String sql, long id) {
        return jdbc.sql(sql).param(id).query(String.class).single();
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

    private long otherCourier() {
        long id = couriers.create("Dana").id();
        courierIds.add(id);
        return id;
    }

    @Test
    void pickupMovesTheAssignedOrderToPickedUp() {
        Offered o = assigned();

        delivery.pickedUp(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("PICKED_UP");
        assertThat(courierStatus(o.courierId())).isEqualTo("BUSY");
    }

    @Test
    void deliveryClosesTheOrderAndFreesTheCourier() {
        Offered o = assigned();
        delivery.pickedUp(o.orderId(), o.courierId());

        delivery.delivered(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("DONE");
        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
        assertThat(inGeoSet(o.courierId())).isTrue();
    }

    @Test
    void anotherCouriersEventsChangeNothing() {
        Offered o = assigned();
        long other = otherCourier();

        delivery.pickedUp(o.orderId(), other);
        assertThat(dispatchStatus(o.orderId())).isEqualTo("ASSIGNED");

        delivery.pickedUp(o.orderId(), o.courierId());
        delivery.delivered(o.orderId(), other);
        assertThat(dispatchStatus(o.orderId())).isEqualTo("PICKED_UP");
        assertThat(courierStatus(o.courierId())).isEqualTo("BUSY");
    }

    @Test
    void aDeliveryBeforeItsPickupClosesTheOrderAndTheLatePickupIsSkipped() {
        Offered o = assigned();

        delivery.delivered(o.orderId(), o.courierId());
        delivery.pickedUp(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("DONE");
        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
    }

    @Test
    void aRedeliveredDeliveryDoesNotFreeTheCourierAgain() {
        Offered first = assigned();
        delivery.pickedUp(first.orderId(), first.courierId());
        delivery.delivered(first.orderId(), first.courierId());
        // The freed courier takes the next order, so they are BUSY again.
        Offered next = offeredTo(first.courierId());

        delivery.delivered(first.orderId(), first.courierId());

        assertThat(courierStatus(first.courierId())).isEqualTo("BUSY");
        assertThat(value("SELECT status FROM offers WHERE id = ?", next.offerId()))
                .isEqualTo("OFFERED");
    }

    /** An AVAILABLE courier a little further from the shop, in the geo set, waiting for the next offer. */
    private long nextCourier() {
        long id = couriers.create("Eli").id();
        courierIds.add(id);
        couriers.moveTo(id, new Position(30.2849, -97.7341));
        couriers.changeStatus(id, CourierStatus.AVAILABLE);
        await().atMost(Duration.ofSeconds(5)).until(() -> inGeoSet(id));
        return id;
    }

    /**
     * The order side has committed the pickup, but dispatch has not handled {@code OrderPickedUp} yet when the courier
     * goes OFFLINE. Dispatch still says ASSIGNED, so the order is unassigned and offered to {@code next}.
     */
    private Offered pickedUpThenOfflineBeforeDispatchHears(long next) {
        Offered o = assigned();
        await().atMost(Duration.ofSeconds(10)).until(() -> orderCourier(o.orderId()) != null);
        jdbc.sql("UPDATE orders SET status = 'PICKED_UP' WHERE id = ?")
                .param(o.orderId())
                .update();
        couriers.changeStatus(o.courierId(), CourierStatus.OFFLINE);
        assertThat(dispatchStatus(o.orderId())).isEqualTo("WAITING");
        assertThat(value("SELECT courier_id::text FROM offers WHERE order_id = ? AND status = 'OFFERED'", o.orderId()))
                .isEqualTo(Long.toString(next));
        return o;
    }

    private long offerOf(long orderId, long courierId) {
        return jdbc.sql("SELECT id FROM offers WHERE order_id = ? AND courier_id = ?")
                .params(orderId, courierId)
                .query(Long.class)
                .single();
    }

    private long openOffers(long orderId) {
        return jdbc.sql("SELECT count(*) FROM offers WHERE order_id = ? AND status = 'OFFERED'")
                .param(orderId)
                .query(Long.class)
                .single();
    }

    private String dispatchCourier(long orderId) {
        return value("SELECT courier_id::text FROM dispatch_orders WHERE order_id = ?", orderId);
    }

    @Test
    void aLatePickupTakesTheOrderBackFromTheNextOffer() {
        long next = nextCourier();
        Offered o = pickedUpThenOfflineBeforeDispatchHears(next);
        long nextOffer = offerOf(o.orderId(), next);

        delivery.pickedUp(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("PICKED_UP");
        assertThat(dispatchCourier(o.orderId())).isEqualTo(Long.toString(o.courierId()));
        assertThat(courierStatus(o.courierId())).isEqualTo("BUSY");
        assertThat(value("SELECT status FROM offers WHERE id = ?", nextOffer)).isEqualTo("WITHDRAWN");
        assertThat(openOffers(o.orderId())).isZero();
        assertThat(courierStatus(next)).isEqualTo("AVAILABLE");
        await().atMost(Duration.ofSeconds(5)).until(() -> inGeoSet(next));

        delivery.delivered(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("DONE");
        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
    }

    @Test
    void aLatePickupTakesTheOrderBackFromTheNextCourierWhoAccepted() {
        long next = nextCourier();
        Offered o = pickedUpThenOfflineBeforeDispatchHears(next);
        assertThat(post("/api/v1/couriers/{c}/offers/{o}/accept", next, offerOf(o.orderId(), next)))
                .hasStatusOk();
        assertThat(dispatchCourier(o.orderId())).isEqualTo(Long.toString(next));

        delivery.pickedUp(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("PICKED_UP");
        assertThat(dispatchCourier(o.orderId())).isEqualTo(Long.toString(o.courierId()));
        assertThat(courierStatus(o.courierId())).isEqualTo("BUSY");
        assertThat(courierStatus(next)).isEqualTo("AVAILABLE");
        assertThat(openOffers(o.orderId())).isZero();
        // The order side never let the next courier in: its CourierAssigned is skipped for a PICKED_UP order.
        assertThat(orderCourier(o.orderId())).isEqualTo(o.courierId());

        delivery.delivered(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("DONE");
        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
        assertThat(courierStatus(next)).isEqualTo("AVAILABLE");
    }

    @Test
    void aRepeatedPickupAfterTheDeliveryChangesNothing() {
        Offered o = assigned();
        delivery.pickedUp(o.orderId(), o.courierId());
        delivery.delivered(o.orderId(), o.courierId());

        delivery.pickedUp(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("DONE");
        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
    }

    @Test
    void aDeliveryBeforeTheLatePickupClosesTheOrderAndFreesTheNextCourier() {
        long next = nextCourier();
        Offered o = pickedUpThenOfflineBeforeDispatchHears(next);
        long nextOffer = offerOf(o.orderId(), next);

        delivery.delivered(o.orderId(), o.courierId());
        delivery.pickedUp(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("DONE");
        assertThat(dispatchCourier(o.orderId())).isEqualTo(Long.toString(o.courierId()));
        assertThat(value("SELECT status FROM offers WHERE id = ?", nextOffer)).isEqualTo("WITHDRAWN");
        assertThat(openOffers(o.orderId())).isZero();
        assertThat(courierStatus(next)).isEqualTo("AVAILABLE");
        // They went OFFLINE themselves; delivering does not put them back to work.
        assertThat(courierStatus(o.courierId())).isEqualTo("OFFLINE");
    }

    @Test
    void aLatePickupWithdrawsTheCouriersOfferOfAnotherOrder() {
        long next = nextCourier();
        Offered o = pickedUpThenOfflineBeforeDispatchHears(next);
        couriers.changeStatus(o.courierId(), CourierStatus.AVAILABLE);
        Offered other = offeredTo(o.courierId());

        delivery.pickedUp(o.orderId(), o.courierId());

        assertThat(value("SELECT status FROM offers WHERE id = ?", other.offerId()))
                .isEqualTo("WITHDRAWN");
        assertThat(dispatchStatus(other.orderId())).isEqualTo("WAITING");
        assertThat(courierStatus(o.courierId())).isEqualTo("BUSY");

        delivery.delivered(o.orderId(), o.courierId());

        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
    }

    @Test
    void deliveringOneOrderKeepsACourierWithAnotherOpenOrderBusy() {
        long next = nextCourier();
        Offered o = pickedUpThenOfflineBeforeDispatchHears(next);
        couriers.changeStatus(o.courierId(), CourierStatus.AVAILABLE);
        Offered other = offeredTo(o.courierId());
        assertThat(post("/api/v1/couriers/{c}/offers/{o}/accept", o.courierId(), other.offerId()))
                .hasStatusOk();
        delivery.pickedUp(o.orderId(), o.courierId());

        delivery.delivered(o.orderId(), o.courierId());

        assertThat(dispatchStatus(o.orderId())).isEqualTo("DONE");
        assertThat(dispatchStatus(other.orderId())).isEqualTo("ASSIGNED");
        assertThat(courierStatus(o.courierId())).isEqualTo("BUSY");

        delivery.delivered(other.orderId(), o.courierId());

        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
    }

    /** A new READY order offered to {@code courierId}, who must be AVAILABLE and at the shop. */
    private Offered offeredTo(long courierId) {
        long customerId = OrderFixtures.customer(jdbc);
        long merchantId = OrderFixtures.merchant(jdbc, true);
        customerIds.add(customerId);
        merchantIds.add(merchantId);
        long orderId = OrderFixtures.order(
                jdbc, customerId, merchantId, "READY", 399, Instant.now().toString());
        orderIds.add(orderId);
        jdbc.sql("INSERT INTO dispatch_orders (order_id, merchant_name, merchant_address, pickup_lat, pickup_lng,"
                        + " delivery_address, delivery_lat, delivery_lng, ready_at)"
                        + " VALUES (?, 'Taco Shop', '100 Congress Ave', 30.2672, -97.7431, '200 Lavaca St', 30.2669,"
                        + " -97.7466, now())")
                .param(orderId)
                .update();
        // Dispatch finds couriers through the geo set, which an after-commit GEOADD fills; make sure it has landed.
        await().atMost(Duration.ofSeconds(5)).until(() -> inGeoSet(courierId));
        assertThat(dispatch.dispatch(orderId)).isTrue();
        long offerId = jdbc.sql("SELECT id FROM offers WHERE order_id = ? AND courier_id = ?")
                .params(orderId, courierId)
                .query(Long.class)
                .single();
        return new Offered(courierId, orderId, offerId);
    }

    @Test
    void theCourierPicksUpAndDeliversThroughTheApi() {
        Offered o = assigned();
        await().atMost(Duration.ofSeconds(10)).until(() -> orderCourier(o.orderId()) != null);

        assertThat(post("/api/v1/couriers/{c}/orders/{o}/pick-up", o.courierId(), o.orderId()))
                .hasStatusOk();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> dispatchStatus(o.orderId()).equals("PICKED_UP"));
        assertThat(mvc.patch()
                        .uri("/api/v1/couriers/{id}/status", o.courierId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"OFFLINE"}
                                """)
                        .exchange())
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("COURIER_HAS_ACTIVE_DELIVERY");

        assertThat(post("/api/v1/couriers/{c}/orders/{o}/deliver", o.courierId(), o.orderId()))
                .hasStatusOk();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> dispatchStatus(o.orderId()).equals("DONE"));
        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
        await().atMost(Duration.ofSeconds(5)).until(() -> inGeoSet(o.courierId()));
    }
}
