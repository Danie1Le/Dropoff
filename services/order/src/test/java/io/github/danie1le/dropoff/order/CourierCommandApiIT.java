package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.contracts.OrderDelivered;
import io.github.danie1le.dropoff.contracts.OrderPickedUp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The courier's pick-up and deliver commands (M3-5, ADR-0005), on orders whose courier is written straight to the row,
 * as the {@code CourierAssigned} listener leaves it. Not rolled back: the events reach their listeners only after
 * commit, so each test deletes its rows instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnableScenarios
@Import(TestcontainersConfiguration.class)
class CourierCommandApiIT {

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;

    CourierCommandApiIT(@Autowired MockMvcTester mvc, @Autowired JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @AfterEach
    void cleanUp() {
        OrderCommandApiIT.deleteAll(jdbc);
    }

    private record Assigned(long courierId, long orderId) {}

    /** A READY order with a courier on it. */
    private Assigned assigned() {
        long courierId = courier();
        long orderId = OrderFixtures.order(
                jdbc,
                OrderFixtures.customer(jdbc),
                OrderFixtures.merchant(jdbc, true),
                "READY",
                399,
                Instant.now().toString());
        jdbc.sql("UPDATE orders SET courier_id = ? WHERE id = ?")
                .params(courierId, orderId)
                .update();
        return new Assigned(courierId, orderId);
    }

    /** Couriers live in dispatch-service since M5-4; the order only stores the id. */
    private static long courier() {
        return OrderFixtures.courierId();
    }

    private MvcTestResult command(long courierId, long orderId, String command) {
        return mvc.post()
                .uri("/api/v1/couriers/{c}/orders/{o}/{command}", courierId, orderId, command)
                .exchange();
    }

    private static void assertStatus(MvcTestResult result, String status) {
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status);
    }

    private static void assertError(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    }

    private List<String> events(long orderId) {
        return jdbc.sql("SELECT from_status || '>' || to_status || ':' || actor FROM order_events WHERE order_id = ?"
                        + " ORDER BY id")
                .param(orderId)
                .query(String.class)
                .list();
    }

    @Test
    void theAssignedCourierPicksUpThenDelivers() {
        Assigned a = assigned();

        MvcTestResult pickedUp = command(a.courierId(), a.orderId(), "pick-up");
        assertStatus(pickedUp, "PICKED_UP");
        assertThat(pickedUp).bodyJson().extractingPath("$.courierId").isEqualTo(a.courierId());
        assertStatus(command(a.courierId(), a.orderId(), "deliver"), "DELIVERED");

        assertThat(events(a.orderId())).containsExactly("READY>PICKED_UP:COURIER", "PICKED_UP>DELIVERED:COURIER");
    }

    @Test
    void aRepeatedCommandIsANoOp() {
        Assigned a = assigned();
        assertStatus(command(a.courierId(), a.orderId(), "pick-up"), "PICKED_UP");

        assertStatus(command(a.courierId(), a.orderId(), "pick-up"), "PICKED_UP");
        assertStatus(command(a.courierId(), a.orderId(), "deliver"), "DELIVERED");
        assertStatus(command(a.courierId(), a.orderId(), "deliver"), "DELIVERED");

        assertThat(events(a.orderId())).hasSize(2);
    }

    @Test
    void deliveringBeforePickingUpIsAConflict() {
        Assigned a = assigned();

        assertError(command(a.courierId(), a.orderId(), "deliver"), HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
        assertThat(events(a.orderId())).isEmpty();
    }

    @Test
    void pickingUpADeliveredOrderIsAConflict() {
        Assigned a = assigned();
        assertStatus(command(a.courierId(), a.orderId(), "pick-up"), "PICKED_UP");
        assertStatus(command(a.courierId(), a.orderId(), "deliver"), "DELIVERED");

        assertError(command(a.courierId(), a.orderId(), "pick-up"), HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION");
    }

    @Test
    void anotherCourierGetsNotFound() {
        Assigned a = assigned();
        long other = courier();

        assertError(command(other, a.orderId(), "pick-up"), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        assertStatus(command(a.courierId(), a.orderId(), "pick-up"), "PICKED_UP");
        assertError(command(other, a.orderId(), "deliver"), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        assertError(command(a.courierId(), Long.MAX_VALUE, "pick-up"), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        assertThat(events(a.orderId())).containsExactly("READY>PICKED_UP:COURIER");
    }

    @Test
    void anOrderWithNoCourierIsNotFound() {
        long courierId = courier();
        long orderId = OrderFixtures.order(
                jdbc,
                OrderFixtures.customer(jdbc),
                OrderFixtures.merchant(jdbc, true),
                "READY",
                399,
                Instant.now().toString());

        assertError(command(courierId, orderId, "pick-up"), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
    }

    @Test
    void publishesOrderPickedUp(Scenario scenario) {
        Assigned a = assigned();
        scenario.stimulate(() -> assertStatus(command(a.courierId(), a.orderId(), "pick-up"), "PICKED_UP"))
                .andWaitForEventOfType(OrderPickedUp.class)
                .matching(e -> e.orderId() == a.orderId())
                .toArriveAndVerify(e -> {
                    assertThat(e.courierId()).isEqualTo(a.courierId());
                    assertThat(e.eventId()).isNotNull();
                    assertThat(e.occurredAt()).isNotNull();
                });
    }

    @Test
    void publishesOrderDelivered(Scenario scenario) {
        Assigned a = assigned();
        assertStatus(command(a.courierId(), a.orderId(), "pick-up"), "PICKED_UP");
        scenario.stimulate(() -> assertStatus(command(a.courierId(), a.orderId(), "deliver"), "DELIVERED"))
                .andWaitForEventOfType(OrderDelivered.class)
                .matching(e -> e.orderId() == a.orderId())
                .toArriveAndVerify(e -> {
                    assertThat(e.courierId()).isEqualTo(a.courierId());
                    assertThat(e.eventId()).isNotNull();
                    assertThat(e.occurredAt()).isNotNull();
                });
    }
}
