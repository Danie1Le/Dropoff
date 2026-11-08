package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives a courier bot with a fake clock (plain {@link Instant}s) and a fake API: no sleeps, no HTTP. */
class CourierBotTest {

    private static final long COURIER = 7;
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private static final LatLng HOME = new LatLng(30.2672, -97.7431);
    private static final LatLng PICKUP = HOME.moveToward(new LatLng(30.3672, -97.7431), 1_000);
    private static final LatLng DROPOFF = PICKUP.moveToward(new LatLng(30.4672, -97.7431), 500);

    private final FakeApi api = new FakeApi();
    private final Deque<OfferDecision> decisions = new ArrayDeque<>();
    private final CourierBot bot = new CourierBot(COURIER, HOME, api, decisions::remove, new Stats());

    @BeforeEach
    void offlineCourierAtHome() {
        api.couriers.put(COURIER, new CourierView(CourierStatus.OFFLINE, HOME));
    }

    private static Instant at(double seconds) {
        return T0.plus(Duration.ofMillis(Math.round(seconds * 1_000)));
    }

    /** Ticks once a second from {@code from} to {@code to}, both inclusive. */
    private void tickEverySecond(int from, int to) {
        for (int s = from; s <= to; s++) {
            bot.tick(at(s));
        }
    }

    private Offer offer(long offerId, Instant expiresAt) {
        return new Offer(offerId, 100 + offerId, PICKUP, DROPOFF, expiresAt);
    }

    @Test
    void goesAvailableAtItsServerPositionOnTheFirstTick() {
        LatLng elsewhere = new LatLng(30.25, -97.75);
        api.couriers.put(COURIER, new CourierView(CourierStatus.OFFLINE, elsewhere));

        bot.tick(T0);

        assertThat(api.calls).containsExactly("courier 7", "status 7 AVAILABLE");
        assertThat(bot.state()).isEqualTo(CourierBot.State.IDLE);
        assertThat(bot.position()).isEqualTo(elsewhere);
    }

    @Test
    void anAlreadyAvailableCourierIsNotPatchedAgain() {
        api.couriers.put(COURIER, new CourierView(CourierStatus.AVAILABLE, HOME));

        bot.tick(T0);

        assertThat(api.callsStartingWith("status")).isEmpty();
        assertThat(bot.state()).isEqualTo(CourierBot.State.IDLE);
    }

    @Test
    void pollsForAnOfferEveryTwoSeconds() {
        bot.tick(T0);
        tickEverySecond(1, 6);

        // Polls at 1 s (first idle tick), 3 s and 5 s.
        assertThat(api.callsStartingWith("offer")).hasSize(3);
    }

    @Test
    void acceptsThenDrivesToPickupAtThirtyKmPerHourSendingALocationEveryThreeSeconds() {
        bot.tick(T0);
        api.offers.put(COURIER, offer(1, at(31)));
        decisions.add(OfferDecision.ACCEPT);

        bot.tick(at(1));
        assertThat(api.calls).contains("accept 7 1");
        assertThat(bot.state()).isEqualTo(CourierBot.State.TO_PICKUP);

        // 60 s at 30 km/h is 500 m.
        tickEverySecond(2, 61);
        assertThat(HOME.distanceMeters(bot.position())).isCloseTo(500, within(1.0));
        assertThat(api.locations).hasSize(20);
        assertThat(api.callsStartingWith("pickUp")).isEmpty();
    }

    @Test
    void picksUpOnArrivalThenDeliversAndWaitsForTheNextOffer() {
        bot.tick(T0);
        api.offers.put(COURIER, offer(1, at(31)));
        decisions.add(OfferDecision.ACCEPT);
        bot.tick(at(1));

        // 1 km to the pickup takes 120 s.
        tickEverySecond(2, 121);
        assertThat(api.calls).contains("pickUp 7 101");
        assertThat(api.locations.getLast()).isEqualTo(PICKUP);
        assertThat(bot.state()).isEqualTo(CourierBot.State.TO_DROPOFF);

        // 500 m more to the delivery address takes 60 s.
        tickEverySecond(122, 181);
        assertThat(api.calls).contains("deliver 7 101");
        assertThat(api.locations.getLast()).isEqualTo(DROPOFF);
        assertThat(bot.position()).isEqualTo(DROPOFF);
        assertThat(bot.state()).isEqualTo(CourierBot.State.IDLE);
        assertThat(api.calls.indexOf("pickUp 7 101")).isLessThan(api.calls.indexOf("deliver 7 101"));
    }

    @Test
    void aFasterSpeedCoversMoreGroundPerSecond() {
        CourierBot fast = new CourierBot(COURIER, HOME, api, decisions::remove, new Stats(), 120);
        fast.tick(T0);
        api.offers.put(COURIER, offer(1, at(31)));
        decisions.add(OfferDecision.ACCEPT);
        fast.tick(at(1));

        // 15 s at 120 km/h is 500 m.
        fast.tick(at(16));

        assertThat(HOME.distanceMeters(fast.position())).isCloseTo(500, within(1.0));
    }

    @Test
    void declinesAndKeepsPolling() {
        bot.tick(T0);
        api.offers.put(COURIER, offer(1, at(31)));
        decisions.add(OfferDecision.DECLINE);

        bot.tick(at(1));
        tickEverySecond(2, 5);

        assertThat(api.calls).contains("decline 7 1");
        assertThat(bot.state()).isEqualTo(CourierBot.State.IDLE);
        assertThat(api.callsStartingWith("offer")).hasSize(3);
    }

    @Test
    void ignoresAnOfferThenGoesAvailableThirtySecondsAfterTheTimeoutSetsItOffline() {
        bot.tick(T0);
        api.offers.put(COURIER, offer(1, at(31)));
        decisions.add(OfferDecision.IGNORE);
        bot.tick(at(1));
        assertThat(bot.state()).isEqualTo(CourierBot.State.AWAITING_TIMEOUT);

        // No calls at all until the offer expires.
        api.calls.clear();
        tickEverySecond(2, 30);
        assertThat(api.calls).isEmpty();

        // The timeout job has not run yet at 31 s, so the courier is still BUSY.
        api.couriers.put(COURIER, new CourierView(CourierStatus.BUSY, HOME));
        bot.tick(at(31));
        assertThat(bot.state()).isEqualTo(CourierBot.State.AWAITING_TIMEOUT);

        // The job sets it OFFLINE; the bot sees that on its next check and waits 30 s.
        api.offers.remove(COURIER);
        api.couriers.put(COURIER, new CourierView(CourierStatus.OFFLINE, HOME));
        tickEverySecond(32, 33);
        assertThat(bot.state()).isEqualTo(CourierBot.State.COOLING_DOWN);

        tickEverySecond(34, 62);
        assertThat(api.callsStartingWith("status")).isEmpty();
        bot.tick(at(63));
        assertThat(api.callsStartingWith("status")).containsExactly("status 7 AVAILABLE");
        assertThat(bot.state()).isEqualTo(CourierBot.State.IDLE);
    }

    @Test
    void aLateAcceptChecksTheStatusAndCoolsDownWhenTheTimeoutAlreadySetItOffline() {
        bot.tick(T0);
        api.offers.put(COURIER, offer(1, at(31)));
        decisions.add(OfferDecision.ACCEPT);
        api.failOn = "accept";
        api.failure = new ApiException(409, "OFFER_CLOSED");
        api.couriers.put(COURIER, new CourierView(CourierStatus.OFFLINE, HOME));

        bot.tick(at(1));
        tickEverySecond(2, 3);

        assertThat(bot.state()).isEqualTo(CourierBot.State.COOLING_DOWN);
        assertThat(api.callsStartingWith("status")).containsExactly("status 7 AVAILABLE");
    }

    @Test
    void aFailedPickUpKeepsTheJobAndRetriesUntilItDelivers() {
        bot.tick(T0);
        api.offers.put(COURIER, offer(1, at(31)));
        decisions.add(OfferDecision.ACCEPT);
        bot.tick(at(1));
        // The async assign has not landed yet when the courier arrives at 121 s.
        api.failOn = "pickUp";
        api.failure = new ApiException(404, "ORDER_NOT_FOUND");

        tickEverySecond(2, 122);
        assertThat(api.callsStartingWith("pickUp")).hasSize(1);
        assertThat(bot.state()).isEqualTo(CourierBot.State.TO_PICKUP);

        // Retried 2 s after the failure, then on to the delivery address.
        bot.tick(at(123));
        assertThat(api.callsStartingWith("pickUp")).containsExactly("pickUp 7 101", "pickUp 7 101");
        assertThat(bot.state()).isEqualTo(CourierBot.State.TO_DROPOFF);

        tickEverySecond(124, 183);
        assertThat(api.calls).contains("deliver 7 101");
        assertThat(api.callsStartingWith("courier")).hasSize(1);
        assertThat(bot.state()).isEqualTo(CourierBot.State.IDLE);
    }

    @Test
    void aFailedDeliverIsRetriedRatherThanDropped() {
        bot.tick(T0);
        api.offers.put(COURIER, offer(1, at(31)));
        decisions.add(OfferDecision.ACCEPT);
        bot.tick(at(1));
        api.failOn = "deliver";
        api.failure = new ApiException(503, null);

        tickEverySecond(2, 185);

        assertThat(api.callsStartingWith("deliver")).containsExactly("deliver 7 101", "deliver 7 101");
        assertThat(bot.state()).isEqualTo(CourierBot.State.IDLE);
    }

    @Test
    void anIdleCourierFarFromDowntownDriftsBackUntilWithinOneKilometre() {
        LatLng edge = Austin.DOWNTOWN.moveToward(new LatLng(30.3672, -97.7431), 3_000);
        api.couriers.put(COURIER, new CourierView(CourierStatus.OFFLINE, edge));

        bot.tick(T0);
        tickEverySecond(1, 60);
        // 60 s at 30 km/h is 500 m closer, with a location every 3 s so dispatch sees the move.
        assertThat(Austin.DOWNTOWN.distanceMeters(bot.position())).isCloseTo(2_500, within(1.0));
        assertThat(api.locations).hasSize(20);

        tickEverySecond(61, 600);
        assertThat(Austin.DOWNTOWN.distanceMeters(bot.position())).isCloseTo(1_000, within(1.0));
        int sent = api.locations.size();
        tickEverySecond(601, 630);
        assertThat(api.locations).hasSize(sent);
        assertThat(bot.state()).isEqualTo(CourierBot.State.IDLE);
    }

    @Test
    void theSameDecisionsGiveTheSameCalls() {
        List<String> first = runScript();
        List<String> second = runScript();

        assertThat(first).isEqualTo(second).isNotEmpty();
    }

    private static List<String> runScript() {
        FakeApi fake = new FakeApi();
        fake.couriers.put(COURIER, new CourierView(CourierStatus.OFFLINE, HOME));
        java.util.Random random = new java.util.Random(42);
        CourierBot seeded = new CourierBot(COURIER, HOME, fake, () -> OfferDecision.roll(random), new Stats());
        for (int s = 0; s <= 600; s++) {
            if (s % 40 == 0) {
                fake.offers.put(COURIER, new Offer(s, 1000 + s, PICKUP, DROPOFF, at(s + 30)));
            }
            seeded.tick(at(s));
        }
        return fake.calls;
    }
}
