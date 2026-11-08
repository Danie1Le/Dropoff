package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmokeTest {

    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private static final LatLng SHOP = new LatLng(30.27, -97.74);
    private static final SimState STATE = new SimState(
            42,
            List.of(new SimState.MerchantState(1, SHOP, List.of(11L, 12L, 13L))),
            List.of(101L),
            List.of(new SimState.CourierState(201, new LatLng(30.26, -97.73))));

    private final FakeApi api = new FakeApi();

    private Smoke smoke(int orders) {
        return new Smoke(STATE, orders, Smoke.DEFAULT_SPEED_KMH, 42, api);
    }

    /** Orders get ids 1, 2, … from the fake, in the order they are placed. */
    private void setStatuses(String... statuses) {
        for (int i = 0; i < statuses.length; i++) {
            api.orderStatuses.put((long) i + 1, statuses[i]);
        }
    }

    @Test
    void retriesAnOrderRejectedUntilTheMerchantCopiesCatchUp() {
        Smoke smoke = smoke(1);
        api.failOn = "placeOrder";
        api.failure = new ApiException(422, "MENU_ITEM_UNKNOWN");

        smoke.tick(T0);
        smoke.tick(T0.plusSeconds(1));
        assertThat(api.callsStartingWith("placeOrder")).hasSize(1);
        assertThat(smoke.statuses()).isEmpty();

        smoke.tick(T0.plus(Smoke.RETRY));

        assertThat(api.callsStartingWith("placeOrder")).hasSize(2);
        assertThat(smoke.statuses()).containsOnlyKeys(1L);
    }

    @Test
    void retriesATransportFailureWithTheSameKeyAndBody() {
        Smoke smoke = smoke(1);
        api.failOn = "placeOrder";
        api.failure = new IllegalStateException("timeout");

        smoke.tick(T0);
        smoke.tick(T0.plus(Smoke.RETRY));

        assertThat(api.idempotencyKeys).hasSize(2).containsOnly(api.idempotencyKeys.get(0));
        assertThat(smoke.statuses()).containsOnlyKeys(1L);
    }

    @Test
    void usesANewKeyAfterTheServerRejectedTheOrder() {
        Smoke smoke = smoke(1);
        api.failOn = "placeOrder";
        api.failure = new ApiException(422, "MENU_ITEM_UNKNOWN");

        smoke.tick(T0);
        smoke.tick(T0.plus(Smoke.RETRY));

        assertThat(api.idempotencyKeys).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void reportsAStaleStateFile() {
        assertThat(smoke(1).stateProblem()).contains("state file is stale");

        api.couriers.put(201L, new CourierView(CourierStatus.OFFLINE, SHOP));

        assertThat(smoke(1).stateProblem()).isNull();
    }

    @Test
    void placesTheGivenNumberOfOrdersAndNoMore() {
        Smoke smoke = smoke(3);

        for (int s = 0; s < 10; s++) {
            smoke.tick(T0.plusSeconds(s));
        }

        assertThat(api.callsStartingWith("placeOrder")).hasSize(3);
        assertThat(api.orders).allSatisfy(order -> {
            assertThat(order.merchantId()).isEqualTo(1);
            assertThat(order.lines()).isNotEmpty();
        });
    }

    @Test
    void passesOnlyOnceEveryOrderIsDelivered() {
        Smoke smoke = smoke(2);
        setStatuses("DELIVERED", "PICKED_UP");
        smoke.tick(T0);
        smoke.tick(T0.plusSeconds(1));
        assertThat(smoke.finished()).isFalse();

        setStatuses("DELIVERED", "DELIVERED");
        smoke.tick(T0.plus(Smoke.RETRY).plusSeconds(1));

        assertThat(smoke.finished()).isTrue();
        assertThat(smoke.passed()).isTrue();
    }

    @Test
    void aCancelledOrderEndsTheTestAsAFailure() {
        Smoke smoke = smoke(1);
        setStatuses("CANCELLED");

        smoke.tick(T0);
        smoke.tick(T0.plusSeconds(1));

        assertThat(smoke.finished()).isTrue();
        assertThat(smoke.passed()).isFalse();
    }

    @Test
    void itsCouriersAcceptEveryOffer() {
        Smoke smoke = smoke(1);
        api.couriers.put(201L, new CourierView(CourierStatus.AVAILABLE, new LatLng(30.26, -97.73)));
        for (int i = 0; i < 5; i++) {
            api.offers.put(201L, new Offer(i + 1, 50 + i, SHOP, SHOP, T0.plusSeconds(30)));
            smoke.tick(T0.plus(Duration.ofMinutes(i)));
        }

        assertThat(api.callsStartingWith("decline")).isEmpty();
        assertThat(api.callsStartingWith("accept 201")).isNotEmpty();
    }
}
