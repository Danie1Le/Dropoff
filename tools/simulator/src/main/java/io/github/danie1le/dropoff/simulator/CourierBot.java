package io.github.danie1le.dropoff.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A courier (ticket #33). It goes AVAILABLE, polls {@code offers/current} every 2 s and answers each offer with an
 * {@link OfferDecision}. After an accept it travels in a straight line at about 30 km/h to the merchant, picks up,
 * travels to the delivery address and delivers, sending a location every 3 s on the way. When an ignored offer times
 * out, the server sets the courier OFFLINE; the bot goes AVAILABLE again 30 s later.
 *
 * <p>While waiting for an offer, a courier more than 1 km from downtown drifts back toward it at the same speed, like a
 * real courier heading for the busy area. Dispatch only searches 5 km around the merchant, so without this a courier
 * who delivered to the edge of the city could wait there forever.
 */
final class CourierBot implements Bot {

    enum State {
        /** Reads the courier from the server and decides where to go from there. */
        STARTING,
        /** AVAILABLE (or BUSY with an offer open), polling for an offer. */
        IDLE,
        TO_PICKUP,
        TO_DROPOFF,
        /** Ignored an offer; waits for the timeout job to set the courier OFFLINE. */
        AWAITING_TIMEOUT,
        /** OFFLINE after a timeout; goes AVAILABLE when the cool-down ends. */
        COOLING_DOWN
    }

    static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    static final Duration LOCATION_INTERVAL = Duration.ofSeconds(3);
    static final Duration COOL_DOWN = Duration.ofSeconds(30);
    /** About 30 km/h (ticket #33); {@code --speed-kmh} changes it for a quicker demo. */
    static final double DEFAULT_SPEED_KMH = 30;

    /** An idle courier drifts toward downtown until it is this close. */
    static final double IDLE_WITHIN_METERS = 1_000;

    /**
     * A failed pick-up, delivery or location send on the way is retried this many times, one {@link #POLL_INTERVAL}
     * apart, before the bot gives up on the job and reads its status again.
     */
    static final int MAX_STEP_RETRIES = 30;

    /** Closer than this counts as arrived, so rounding in the distance maths never costs an extra tick. */
    private static final double ARRIVED_WITHIN_METERS = 1;

    private static final Logger log = LoggerFactory.getLogger(CourierBot.class);

    private final long courierId;
    private final DropoffApi api;
    private final Supplier<OfferDecision> decisions;
    private final Stats stats;
    private final double metersPerSecond;

    private State state = State.STARTING;
    private LatLng position;
    private boolean started;
    private Instant nextCheckAt = Instant.MIN;
    private Instant nextLocationAt = Instant.MIN;
    private Instant lastMovedAt = Instant.MIN;
    private @Nullable Offer job;
    private int stepFailures;

    CourierBot(long courierId, LatLng home, DropoffApi api, Supplier<OfferDecision> decisions, Stats stats) {
        this(courierId, home, api, decisions, stats, DEFAULT_SPEED_KMH);
    }

    CourierBot(
            long courierId,
            LatLng home,
            DropoffApi api,
            Supplier<OfferDecision> decisions,
            Stats stats,
            double speedKmh) {
        this.metersPerSecond = speedKmh * 1_000 / 3_600;
        this.courierId = courierId;
        this.position = home;
        this.api = api;
        this.decisions = decisions;
        this.stats = stats;
    }

    State state() {
        return state;
    }

    LatLng position() {
        return position;
    }

    @Override
    public void tick(Instant now) {
        try {
            switch (state) {
                case STARTING -> {
                    if (isDue(now)) {
                        start(now);
                    }
                }
                case IDLE -> {
                    drift(now);
                    if (isDue(now)) {
                        pollOffer(now);
                    }
                }
                case TO_PICKUP, TO_DROPOFF -> move(now);
                case AWAITING_TIMEOUT -> {
                    if (isDue(now)) {
                        checkTimedOut(now);
                    }
                }
                case COOLING_DOWN -> {
                    if (isDue(now)) {
                        goAvailable(now);
                    }
                }
            }
        } catch (RuntimeException e) {
            stats.errors++;
            if (job != null && (state == State.TO_PICKUP || state == State.TO_DROPOFF) && retryStep(now, e)) {
                return;
            }
            // A late accept, a lost connection or a restart leaves the bot unsure; read the server state next tick.
            log.warn("Courier {} in {}: {}; checking its status again", courierId, state, e.getMessage());
            state = State.STARTING;
            nextCheckAt = now;
        }
    }

    /**
     * Keeps the job after a failed step on the way, such as a 404 on pick-up while the async courier assignment has not
     * landed yet, or a dropped connection on deliver. Resetting to STARTING here would strand the courier BUSY with an
     * accepted offer that {@code offers/current} no longer returns.
     *
     * @return false once the retries run out
     */
    private boolean retryStep(Instant now, RuntimeException e) {
        long orderId = requireJob().orderId();
        stepFailures++;
        if (stepFailures > MAX_STEP_RETRIES) {
            log.error(
                    "Courier {} gave up on order {} in {} after {} retries: {}",
                    courierId,
                    orderId,
                    state,
                    MAX_STEP_RETRIES,
                    e.getMessage());
            stepFailures = 0;
            return false;
        }
        log.warn(
                "Courier {} in {} for order {}: {}; retrying in {} s",
                courierId,
                state,
                orderId,
                e.getMessage(),
                POLL_INTERVAL.toSeconds());
        nextCheckAt = now.plus(POLL_INTERVAL);
        nextLocationAt = nextCheckAt;
        return true;
    }

    private Offer requireJob() {
        Offer current = job;
        if (current == null) {
            throw new IllegalStateException("moving without a job");
        }
        return current;
    }

    private boolean isDue(Instant now) {
        return !now.isBefore(nextCheckAt);
    }

    private void start(Instant now) {
        CourierView courier = api.courier(courierId);
        LatLng serverPosition = courier.position();
        if (serverPosition != null) {
            position = serverPosition;
        }
        if (courier.status() != CourierStatus.OFFLINE) {
            // AVAILABLE, or BUSY with an offer the next poll will find.
            idle(now);
        } else if (started) {
            // Only a timeout sets a running bot OFFLINE.
            coolDown(now);
        } else {
            if (serverPosition == null) {
                api.updateLocation(courierId, position);
            }
            goAvailable(now);
        }
        started = true;
    }

    private void goAvailable(Instant now) {
        api.changeStatus(courierId, CourierStatus.AVAILABLE);
        idle(now);
    }

    private void idle(Instant now) {
        state = State.IDLE;
        job = null;
        nextCheckAt = now;
        lastMovedAt = now;
        nextLocationAt = now.plus(LOCATION_INTERVAL);
    }

    /** Moves an idle courier toward downtown until it is within {@link #IDLE_WITHIN_METERS}. */
    private void drift(Instant now) {
        double seconds = Duration.between(lastMovedAt, now).toMillis() / 1_000.0;
        lastMovedAt = now;
        double excess = position.distanceMeters(Austin.DOWNTOWN) - IDLE_WITHIN_METERS;
        if (excess < ARRIVED_WITHIN_METERS) {
            return;
        }
        position = position.moveToward(Austin.DOWNTOWN, Math.min(excess, metersPerSecond * seconds));
        if (!now.isBefore(nextLocationAt)) {
            // While AVAILABLE, each location also moves the courier in the dispatch geo index.
            api.updateLocation(courierId, position);
            nextLocationAt = now.plus(LOCATION_INTERVAL);
        }
    }

    private void coolDown(Instant now) {
        log.info("Courier {} was set OFFLINE by a timeout; AVAILABLE again in {} s", courierId, COOL_DOWN.toSeconds());
        state = State.COOLING_DOWN;
        nextCheckAt = now.plus(COOL_DOWN);
    }

    private void pollOffer(Instant now) {
        nextCheckAt = now.plus(POLL_INTERVAL);
        Optional<Offer> current = api.currentOffer(courierId);
        if (current.isEmpty()) {
            return;
        }
        Offer offer = current.get();
        switch (decisions.get()) {
            case ACCEPT -> {
                api.acceptOffer(courierId, offer.offerId());
                stats.offersAccepted++;
                log.info("Courier {} accepted order {}", courierId, offer.orderId());
                job = offer;
                state = State.TO_PICKUP;
                stepFailures = 0;
                nextCheckAt = now;
                lastMovedAt = now;
                nextLocationAt = now.plus(LOCATION_INTERVAL);
            }
            case DECLINE -> {
                api.declineOffer(courierId, offer.offerId());
                stats.offersDeclined++;
            }
            case IGNORE -> {
                stats.offersIgnored++;
                state = State.AWAITING_TIMEOUT;
                nextCheckAt = offer.expiresAt();
            }
        }
    }

    private void move(Instant now) {
        Offer current = requireJob();
        LatLng target = state == State.TO_PICKUP ? current.pickup() : current.delivery();
        double seconds = Duration.between(lastMovedAt, now).toMillis() / 1_000.0;
        lastMovedAt = now;
        position = position.moveToward(target, metersPerSecond * seconds);
        boolean arrived = position.distanceMeters(target) < ARRIVED_WITHIN_METERS;
        if (arrived) {
            position = target;
            if (!isDue(now)) {
                // Waiting to retry a failed step.
                return;
            }
        }
        if (arrived || !now.isBefore(nextLocationAt)) {
            api.updateLocation(courierId, position);
            nextLocationAt = now.plus(LOCATION_INTERVAL);
        }
        if (!arrived) {
            return;
        }
        if (state == State.TO_PICKUP) {
            api.pickUp(courierId, current.orderId());
            stepFailures = 0;
            stats.pickedUp++;
            state = State.TO_DROPOFF;
        } else {
            api.deliver(courierId, current.orderId());
            stepFailures = 0;
            stats.delivered++;
            log.info("Courier {} delivered order {}", courierId, current.orderId());
            // The server makes the courier AVAILABLE on delivery.
            idle(now);
        }
    }

    private void checkTimedOut(Instant now) {
        if (api.courier(courierId).status() == CourierStatus.OFFLINE) {
            coolDown(now);
        } else {
            // The timeout job runs every 5 s; look again shortly.
            nextCheckAt = now.plus(POLL_INTERVAL);
        }
    }
}
