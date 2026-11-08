package io.github.danie1le.dropoff.order.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.contracts.CourierAssigned;
import io.github.danie1le.dropoff.contracts.CourierUnassigned;
import io.github.danie1le.dropoff.order.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The order side of a reassignment (M3-4, ticket #85): {@code CourierUnassigned} clears the courier, and the
 * {@code assigned_offer_id} high-water mark keeps stale or reordered assign and unassign events from bringing an earlier
 * courier back. The listeners are called directly, without their async proxies, so each test sees the update at once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CourierUnassignedListenerIT {

    private final JdbcClient jdbc;
    private final CourierAssignedListener assigned;
    private final CourierUnassignedListener unassigned;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> customerIds = new ArrayList<>();
    private final List<Long> merchantIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();

    CourierUnassignedListenerIT(@Autowired JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.assigned = new CourierAssignedListener(jdbc, Clock.systemUTC());
        this.unassigned = new CourierUnassignedListener(jdbc, Clock.systemUTC());
    }

    @AfterEach
    void deleteCreatedRows() {
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

    private long order(String status) {
        long customerId = OrderFixtures.customer(jdbc);
        long merchantId = OrderFixtures.merchant(jdbc, true);
        customerIds.add(customerId);
        merchantIds.add(merchantId);
        long orderId = OrderFixtures.order(
                jdbc, customerId, merchantId, status, 399, Instant.now().toString());
        orderIds.add(orderId);
        return orderId;
    }

    private long courier() {
        long id = jdbc.sql("INSERT INTO couriers (name, status) VALUES ('Carla', 'BUSY') RETURNING id")
                .query(Long.class)
                .single();
        courierIds.add(id);
        return id;
    }

    /** The order's courier, or null. Read with {@code list()}, since {@code single()} rejects a null value. */
    private @Nullable Long courierOf(long orderId) {
        return jdbc.sql("SELECT courier_id FROM orders WHERE id = ?")
                .param(orderId)
                .query((rs, row) -> rs.getObject("courier_id", Long.class))
                .list()
                .get(0);
    }

    /** Assigns the courier for a new accepted offer, and returns its id. */
    private long assign(long orderId, long courierId) {
        long offerId = OrderFixtures.acceptedOffer(jdbc, orderId, courierId);
        assign(orderId, courierId, offerId);
        return offerId;
    }

    private void assign(long orderId, long courierId, long offerId) {
        assigned.on(new CourierAssigned(UUID.randomUUID(), orderId, courierId, offerId, Instant.now()));
    }

    private void unassign(long orderId, long courierId, long offerId) {
        unassigned.on(new CourierUnassigned(UUID.randomUUID(), orderId, courierId, offerId, Instant.now()));
    }

    @Test
    void clearsTheCourierAndKeepsTheOrderReady() {
        long orderId = order("READY");
        long courierId = courier();
        long offerId = assign(orderId, courierId);

        unassign(orderId, courierId, offerId);
        unassign(orderId, courierId, offerId);

        assertThat(courierOf(orderId)).isNull();
        assertThat(jdbc.sql("SELECT status FROM orders WHERE id = ?")
                        .param(orderId)
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
    }

    @Test
    void aRedeliveredAssignAfterTheUnassignDoesNotRestoreTheCourier() {
        long orderId = order("READY");
        long courierId = courier();
        long offerId = assign(orderId, courierId);
        unassign(orderId, courierId, offerId);

        assign(orderId, courierId, offerId);

        assertThat(courierOf(orderId)).isNull();
    }

    @Test
    void aLateUnassignLeavesTheNextCourierInPlace() {
        long orderId = order("READY");
        long first = courier();
        long second = courier();
        long firstOffer = assign(orderId, first);
        assign(orderId, second);

        unassign(orderId, first, firstOffer);

        assertThat(courierOf(orderId)).isEqualTo(second);
    }

    @Test
    void leavesAnOrderThatIsNoLongerReadyAlone() {
        long orderId = order("READY");
        long courierId = courier();
        long offerId = assign(orderId, courierId);
        jdbc.sql("UPDATE orders SET status = 'PICKED_UP' WHERE id = ?")
                .param(orderId)
                .update();

        unassign(orderId, courierId, offerId);

        assertThat(courierOf(orderId)).isEqualTo(courierId);
    }
}
