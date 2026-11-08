package io.github.danie1le.dropoff.dispatch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.danie1le.dropoff.Consumers;
import io.github.danie1le.dropoff.DispatchTestcontainers;
import io.github.danie1le.dropoff.KafkaTestSupport;
import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.dispatch.DispatchFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * Pickup and delivery on dispatch's side (M3-5), against real Postgres, Redis and Kafka. The guarded updates are tested
 * by calling {@link DeliveryService} directly; the last tests send {@code OrderPickedUp} and {@code OrderDelivered} to
 * {@code order-events}, as order-service does, and let the consumer run them.
 * Nothing is rolled back, so each test deletes its rows and takes its couriers out of the geo set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DispatchTestcontainers.class)
class DeliveryIT {

    private final DispatchService dispatch;
    private final DeliveryService delivery;
    private final CourierService couriers;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final MockMvcTester mvc;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

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
        // The events reach their Kafka consumer asynchronously; let them finish before deleting their rows.
        Consumers.awaitSettled(jdbc);
        if (!eventIds.isEmpty()) {
            jdbc.sql("DELETE FROM processed_events WHERE event_id IN (:ids)")
                    .param("ids", eventIds)
                    .update();
            eventIds.clear();
        }
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
        long orderId = DispatchFixtures.waitingOrder(jdbc);
        orderIds.add(orderId);
        // Dispatch finds couriers through the geo set, which an after-commit GEOADD fills; make sure it has landed.
        await().atMost(Duration.ofSeconds(5)).until(() -> inGeoSet(courierId));
        assertThat(dispatch.dispatch(orderId)).isTrue();
        long offerId = jdbc.sql("SELECT id FROM offers WHERE order_id = ? AND courier_id = ?")
                .params(orderId, courierId)
                .query(Long.class)
                .single();
        return new Offered(courierId, orderId, offerId);
    }

    /**
     * Sends an order event to {@code order-events} as order-service would, and waits until dispatch has handled it.
     */
    private void sendOrderEvent(String type, long orderId, long courierId, UUID eventId) {
        byte[] value = "{\"eventId\":\"%s\",\"orderId\":%d,\"courierId\":%d,\"occurredAt\":\"2026-11-08T12:00:00Z\"}"
                .formatted(eventId, orderId, courierId)
                .getBytes(StandardCharsets.UTF_8);
        KafkaTestSupport.send(KafkaTopics.ORDER_EVENTS, Long.toString(orderId), type, value);
        Consumers.awaitSettled(jdbc);
    }

    /** Forgets that dispatch processed the event, as a crash between the step's commit and the event id's would. */
    private void loseProcessedRow(UUID eventId) {
        assertThat(jdbc.sql("DELETE FROM processed_events WHERE consumer_group = 'dispatch-service' AND event_id = ?")
                        .param(eventId)
                        .update())
                .isOne();
    }

    private boolean processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE consumer_group = 'dispatch-service'"
                                + " AND event_id = ?")
                        .param(eventId)
                        .query(Long.class)
                        .single()
                == 1;
    }

    /** Everything a re-run could change: the dispatch row, the courier, and every offer of the order. */
    private String dispatchState(long orderId, long courierId) {
        return jdbc.sql("SELECT d.status || '/' || d.courier_id || '/' || c.status || '/' || ("
                        + "SELECT coalesce(string_agg(o.id || ':' || o.status, ',' ORDER BY o.id), '')"
                        + " FROM offers o WHERE o.order_id = d.order_id OR o.courier_id = c.id)"
                        + " FROM dispatch_orders d, couriers c WHERE d.order_id = ? AND c.id = ?")
                .params(orderId, courierId)
                .query(String.class)
                .single();
    }

    /**
     * The pickup and delivery steps commit before their event id (ticket #15, ADR-0006), so a crash in between makes
     * Kafka redeliver an event whose work is already done. Its status guards must make that second run a no-op.
     */
    @Test
    void aRedeliveredPickupOrDeliveryWhoseEventIdWasLostChangesNothing() {
        Offered o = assigned();

        UUID pickupId = UUID.randomUUID();
        eventIds.add(pickupId);
        sendOrderEvent("OrderPickedUp", o.orderId(), o.courierId(), pickupId);
        assertThat(dispatchStatus(o.orderId())).isEqualTo("PICKED_UP");
        String afterPickup = dispatchState(o.orderId(), o.courierId());

        loseProcessedRow(pickupId);
        sendOrderEvent("OrderPickedUp", o.orderId(), o.courierId(), pickupId);

        assertThat(processed(pickupId)).as("the redelivered pickup ran again").isTrue();
        assertThat(dispatchState(o.orderId(), o.courierId())).isEqualTo(afterPickup);

        UUID deliveryId = UUID.randomUUID();
        eventIds.add(deliveryId);
        sendOrderEvent("OrderDelivered", o.orderId(), o.courierId(), deliveryId);
        assertThat(dispatchStatus(o.orderId())).isEqualTo("DONE");
        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
        // The freed courier takes the next order, so freeing them a second time would show.
        Offered next = offeredTo(o.courierId());
        String afterDelivery = dispatchState(o.orderId(), o.courierId());
        assertThat(courierStatus(o.courierId())).isEqualTo("BUSY");

        loseProcessedRow(deliveryId);
        sendOrderEvent("OrderDelivered", o.orderId(), o.courierId(), deliveryId);

        assertThat(processed(deliveryId))
                .as("the redelivered delivery ran again")
                .isTrue();
        assertThat(dispatchState(o.orderId(), o.courierId())).isEqualTo(afterDelivery);
        assertThat(value("SELECT status FROM offers WHERE id = ?", next.offerId()))
                .isEqualTo("OFFERED");
    }

    @Test
    void thePickupAndDeliveryEventsCloseTheOrderAndFreeTheCourier() {
        Offered o = assigned();
        UUID pickupId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        eventIds.add(pickupId);
        eventIds.add(deliveryId);

        sendOrderEvent("OrderPickedUp", o.orderId(), o.courierId(), pickupId);

        assertThat(dispatchStatus(o.orderId())).isEqualTo("PICKED_UP");
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

        sendOrderEvent("OrderDelivered", o.orderId(), o.courierId(), deliveryId);

        assertThat(dispatchStatus(o.orderId())).isEqualTo("DONE");
        assertThat(courierStatus(o.courierId())).isEqualTo("AVAILABLE");
        await().atMost(Duration.ofSeconds(5)).until(() -> inGeoSet(o.courierId()));
    }
}
