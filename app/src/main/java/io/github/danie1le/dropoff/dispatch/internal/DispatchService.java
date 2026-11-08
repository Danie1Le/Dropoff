package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.OrderReady;
import io.github.danie1le.dropoff.dispatch.internal.CandidateSelection.PastOffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatch: offers a WAITING order to the nearest available courier (M3-2, decisions #11, #13, #14, ticket #42).
 *
 * <p>Each dispatch runs in one transaction that locks the order's {@code dispatch_orders} row with {@code SKIP LOCKED},
 * so the {@code OrderReady} listener and the dispatch-again job never work on the same order at once. Couriers come
 * from the Redis geo set, but Postgres decides: a courier is claimed with {@code UPDATE couriers ... WHERE status =
 * 'AVAILABLE'}, so of two orders racing for one courier only one gets them. The unique index {@code
 * one_open_offer_per_order} is the last guard: a second open offer fails and rolls back its claim with it.
 */
@Service
class DispatchService {

    /** The Dispatch Radius around the merchant (docs/PLAN.md, M3-2). */
    static final Distance RADIUS = new Distance(5, Metrics.KILOMETERS);

    /** How long a courier has to respond to an offer (ticket #11). */
    static final Duration OFFER_TTL = Duration.ofSeconds(30);

    private final JdbcClient jdbc;
    private final CourierGeoIndex geoIndex;
    private final Clock clock;

    DispatchService(JdbcClient jdbc, CourierGeoIndex geoIndex, Clock clock) {
        this.jdbc = jdbc;
        this.geoIndex = geoIndex;
        this.clock = clock;
    }

    /** Stores dispatch's copy of a ready order as WAITING. A redelivered event changes nothing. */
    @Transactional
    void open(OrderReady event) {
        jdbc.sql("INSERT INTO dispatch_orders (order_id, merchant_name, merchant_address, pickup_lat, pickup_lng,"
                        + " delivery_address, delivery_lat, delivery_lng, ready_at)"
                        + " VALUES (:orderId, :merchantName, :merchantAddress, :pickupLat, :pickupLng,"
                        + " :deliveryAddress, :deliveryLat, :deliveryLng, :readyAt)"
                        + " ON CONFLICT (order_id) DO NOTHING")
                .param("orderId", event.orderId())
                .param("merchantName", event.merchantName())
                .param("merchantAddress", event.merchantAddress())
                .param("pickupLat", event.pickupLat())
                .param("pickupLng", event.pickupLng())
                .param("deliveryAddress", event.deliveryAddress())
                .param("deliveryLat", event.deliveryLat())
                .param("deliveryLng", event.deliveryLng())
                .param("readyAt", Timestamp.from(event.readyAt()))
                .update();
    }

    /**
     * Dispatches one order. Does nothing if the order is not WAITING, already has an open offer, or is locked by another
     * dispatch, which is handling it.
     *
     * @return whether an offer was sent
     */
    @Transactional
    boolean dispatch(long orderId) {
        return jdbc.sql("SELECT pickup_lat, pickup_lng FROM dispatch_orders WHERE order_id = ? AND status = 'WAITING'"
                        + " FOR UPDATE SKIP LOCKED")
                .param(orderId)
                .query((rs, row) -> new Position(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")))
                .optional()
                .map(pickup -> offerToNearest(orderId, pickup))
                .orElse(false);
    }

    /**
     * Dispatches the oldest WAITING order that has no open offer and is not in {@code tried} (decision #14). The row is
     * locked with {@code SKIP LOCKED}, so concurrent runs and the listener each take a different order.
     *
     * <p>The picked id is added to {@code tried} before dispatching, so if dispatching throws (and rolls back) the caller
     * still knows which order failed, and the next call steps past it. An order with no courier in range is also
     * stepped past.
     *
     * @return whether an order was picked; {@code false} means none is left
     */
    @Transactional
    boolean dispatchNextWaiting(Set<Long> tried) {
        String skipTried = tried.isEmpty() ? "" : " AND d.order_id NOT IN (:tried)";
        JdbcClient.StatementSpec select = jdbc.sql("SELECT d.order_id, d.pickup_lat, d.pickup_lng"
                + " FROM dispatch_orders d WHERE d.status = 'WAITING'"
                + " AND NOT EXISTS (SELECT 1 FROM offers o WHERE o.order_id = d.order_id AND o.status = 'OFFERED')"
                + skipTried
                + " ORDER BY d.ready_at, d.order_id LIMIT 1 FOR UPDATE OF d SKIP LOCKED");
        if (!tried.isEmpty()) {
            select = select.param("tried", List.copyOf(tried));
        }
        Optional<WaitingOrder> next = select.query((rs, row) -> new WaitingOrder(
                        rs.getLong("order_id"), new Position(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng"))))
                .optional();
        if (next.isEmpty()) {
            return false;
        }
        tried.add(next.get().orderId());
        offerToNearest(next.get().orderId(), next.get().pickup());
        return true;
    }

    private record WaitingOrder(long orderId, Position pickup) {}

    /** Offers a locked WAITING order to the first candidate that can still be claimed. */
    private boolean offerToNearest(long orderId, Position pickup) {
        // A separate statement, so it also sees an offer committed after this transaction's first query.
        if (hasOpenOffer(orderId)) {
            return false;
        }
        Instant now = DbClock.now(clock);
        List<Long> candidates = CandidateSelection.select(geoIndex.nearest(pickup, RADIUS), pastOffers(orderId), now);
        for (long courierId : candidates) {
            if (tryOffer(orderId, courierId, now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Claims the courier and sends them an OFFERED offer expiring after {@link #OFFER_TTL}. The courier leaves the geo
     * set either way: claimed, they are BUSY; not claimed, their member was stale (ticket #83).
     *
     * @return whether the courier was claimed and offered the order
     * @throws org.springframework.dao.DataIntegrityViolationException if the order already has an open offer; the
     *     transaction must roll back, claim included
     */
    boolean tryOffer(long orderId, long courierId, Instant now) {
        int claimed = jdbc.sql("UPDATE couriers SET status = 'BUSY', updated_at = :now"
                        + " WHERE id = :id AND status = 'AVAILABLE'")
                .param("now", Timestamp.from(now))
                .param("id", courierId)
                .update();
        geoIndex.removeAfterCommit(courierId);
        if (claimed == 0) {
            return false;
        }
        jdbc.sql("INSERT INTO offers (order_id, courier_id, status, offered_at, expires_at)"
                        + " VALUES (:orderId, :courierId, 'OFFERED', :now, :expiresAt)")
                .param("orderId", orderId)
                .param("courierId", courierId)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plus(OFFER_TTL)))
                .update();
        return true;
    }

    private boolean hasOpenOffer(long orderId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM offers WHERE order_id = ? AND status = 'OFFERED')")
                .param(orderId)
                .query(Boolean.class)
                .single();
    }

    private List<PastOffer> pastOffers(long orderId) {
        return jdbc.sql("SELECT courier_id, offered_at FROM offers WHERE order_id = ?")
                .param(orderId)
                .query((rs, row) -> new PastOffer(
                        rs.getLong("courier_id"), rs.getTimestamp("offered_at").toInstant()))
                .list();
    }
}
