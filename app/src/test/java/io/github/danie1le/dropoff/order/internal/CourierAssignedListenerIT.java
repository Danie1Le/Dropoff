package io.github.danie1le.dropoff.order.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.contracts.CourierAssigned;
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
 * The order side of an accept (M3-3, ticket #11): {@code CourierAssigned} records the courier, but only on a READY
 * order and only for an offer newer than the one already recorded (ticket #85). The listener is called directly, without its async proxy, so each test sees the update as soon as it returns.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CourierAssignedListenerIT {

    private final JdbcClient jdbc;
    private final CourierAssignedListener listener;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> customerIds = new ArrayList<>();
    private final List<Long> merchantIds = new ArrayList<>();
    private final List<Long> courierIds = new ArrayList<>();

    CourierAssignedListenerIT(@Autowired JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.listener = new CourierAssignedListener(jdbc, Clock.systemUTC());
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
        listener.on(new CourierAssigned(UUID.randomUUID(), orderId, courierId, offerId, Instant.now()));
    }

    private @Nullable Long assignedOfferOf(long orderId) {
        return jdbc.sql("SELECT assigned_offer_id FROM orders WHERE id = ?")
                .param(orderId)
                .query((rs, row) -> rs.getObject("assigned_offer_id", Long.class))
                .list()
                .get(0);
    }

    @Test
    void recordsTheCourierOnAReadyOrder() {
        long orderId = order("READY");
        long courierId = courier();

        long offerId = assign(orderId, courierId);
        assign(orderId, courierId, offerId);

        assertThat(courierOf(orderId)).isEqualTo(courierId);
        assertThat(assignedOfferOf(orderId)).isEqualTo(offerId);
        assertThat(jdbc.sql("SELECT status FROM orders WHERE id = ?")
                        .param(orderId)
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
    }

    @Test
    void leavesAnOrderThatIsNotReadyAlone() {
        long cancelled = order("CANCELLED");
        long preparing = order("PREPARING");
        long courierId = courier();

        assign(cancelled, courierId);
        assign(preparing, courierId);

        assertThat(courierOf(cancelled)).isNull();
        assertThat(courierOf(preparing)).isNull();
    }

    @Test
    void anOlderOfferArrivingLateDoesNotReplaceANewerOne() {
        long orderId = order("READY");
        long first = courier();
        long second = courier();

        long older = OrderFixtures.acceptedOffer(jdbc, orderId, first);
        long newer = OrderFixtures.acceptedOffer(jdbc, orderId, second);

        assign(orderId, second, newer);
        assign(orderId, first, older);

        assertThat(courierOf(orderId)).isEqualTo(second);
        assertThat(assignedOfferOf(orderId)).isEqualTo(newer);
    }
}
