package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CustomerBotTest {

    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private static final SimState STATE = new SimState(
            42,
            List.of(
                    new SimState.MerchantState(1, new LatLng(30.27, -97.74), List.of(11L, 12L, 13L)),
                    new SimState.MerchantState(2, new LatLng(30.28, -97.75), List.of(21L, 22L, 23L))),
            List.of(101L, 102L),
            List.of(new SimState.CourierState(201, new LatLng(30.26, -97.73))));

    private final FakeApi api = new FakeApi();

    @Test
    void placesSixOrdersAMinuteByDefault() {
        CustomerBot bot = new CustomerBot(STATE, 6, api, new Random(42), new Stats());

        for (int s = 0; s < 60; s++) {
            bot.tick(T0.plus(Duration.ofSeconds(s)));
        }

        assertThat(api.callsStartingWith("placeOrder")).hasSize(6);
    }

    @Test
    void ordersOneToThreeItemsFromTheChosenMerchant() {
        CustomerBot bot = new CustomerBot(STATE, 60, api, new Random(42), new Stats());

        for (int s = 0; s < 100; s++) {
            bot.tick(T0.plus(Duration.ofSeconds(s)));
        }

        assertThat(api.orders).hasSize(100);
        for (FakeApi.PlacedOrder order : api.orders) {
            SimState.MerchantState merchant = STATE.merchants().stream()
                    .filter(m -> m.id() == order.merchantId())
                    .findFirst()
                    .orElseThrow();
            assertThat(STATE.customerIds()).contains(order.customerId());
            assertThat(order.lines()).hasSizeBetween(1, 3);
            assertThat(order.lines()).allSatisfy(line -> {
                assertThat(merchant.menuItemIds()).contains(line.menuItemId());
                assertThat(line.quantity()).isBetween(1, 2);
            });
            assertThat(order.lines().stream().map(OrderLine::menuItemId).distinct())
                    .hasSameSizeAs(order.lines());
        }
    }
}
