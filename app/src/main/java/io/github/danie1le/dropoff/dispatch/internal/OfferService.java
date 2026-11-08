package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.contracts.CourierAssigned;
import io.github.danie1le.dropoff.dispatch.internal.OfferResponses.CurrentOffer;
import io.github.danie1le.dropoff.dispatch.internal.OfferResponses.OfferResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A courier's answer to an offer (M3-3, ticket #11): accept or decline, in the path-scoped style of ADR-0005. Another
 * courier's offer is a 404, an offer that is no longer open a 409 {@code OFFER_CLOSED}, and a repeated command a 200
 * no-op.
 *
 * <p>The database decides whether an answer is late: the {@code UPDATE} only matches while {@code expires_at > now()},
 * so there is no gap between checking the deadline and writing the answer, and a race with the timeout job (M3-4)
 * settles on the row lock.
 */
@Service
class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final JdbcClient jdbc;
    private final CourierService couriers;
    private final DispatchService dispatch;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    OfferService(
            JdbcClient jdbc,
            CourierService couriers,
            DispatchService dispatch,
            ApplicationEventPublisher events,
            TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.couriers = couriers;
        this.dispatch = dispatch;
        this.events = events;
        this.transaction = transaction;
    }

    /** The courier's open, unexpired offer with the order's pickup and delivery details, or empty when there is none. */
    @Transactional(readOnly = true)
    Optional<CurrentOffer> current(long courierId) {
        couriers.get(courierId);
        return jdbc.sql("SELECT o.id, o.order_id, o.expires_at, d.merchant_name, d.merchant_address, d.pickup_lat,"
                        + " d.pickup_lng, d.delivery_address, d.delivery_lat, d.delivery_lng"
                        + " FROM offers o JOIN dispatch_orders d ON d.order_id = o.order_id"
                        + " WHERE o.courier_id = ? AND o.status = 'OFFERED' AND o.expires_at > now()"
                        + " ORDER BY o.offered_at DESC LIMIT 1")
                .param(courierId)
                .query((rs, row) -> new CurrentOffer(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getString("merchant_name"),
                        rs.getString("merchant_address"),
                        rs.getDouble("pickup_lat"),
                        rs.getDouble("pickup_lng"),
                        rs.getString("delivery_address"),
                        rs.getDouble("delivery_lat"),
                        rs.getDouble("delivery_lng"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    /**
     * Accepts the offer: the order becomes ASSIGNED to the courier, who stays BUSY, and {@link CourierAssigned} is
     * published for the order module. Only a BUSY courier can accept: one who went OFFLINE while the offer was open
     * gets a 409 {@code COURIER_NOT_BUSY} and the offer stays open.
     *
     * <p>Locks the courier, then the offer, then {@code dispatch_orders}: the lock order every offer command uses.
     */
    @Transactional
    OfferResponse accept(long courierId, long offerId) {
        Courier courier = couriers.lock(courierId);
        Optional<OfferResponse> accepted = courier.status() == CourierStatus.BUSY
                ? respond(courierId, offerId, OfferStatus.ACCEPTED)
                : Optional.empty();
        if (accepted.isEmpty()) {
            return repeatOrClosed(courierId, offerId, OfferStatus.ACCEPTED);
        }
        OfferResponse offer = accepted.get();
        int assigned = jdbc.sql("UPDATE dispatch_orders SET status = 'ASSIGNED', courier_id = :courierId"
                        + " WHERE order_id = :orderId AND status = 'WAITING'")
                .param("courierId", courierId)
                .param("orderId", offer.orderId())
                .update();
        if (assigned != 1) {
            // An open offer implies a WAITING order; anything else is a bug, so roll the accept back.
            throw new IllegalStateException("order " + offer.orderId() + " is not WAITING for its open offer");
        }
        events.publishEvent(
                new CourierAssigned(UUID.randomUUID(), offer.orderId(), courierId, offerId, offer.respondedAt()));
        return offer;
    }

    /**
     * Declines the offer: the courier becomes AVAILABLE again and goes back in the geo set, then the order is
     * dispatched to someone else. The new dispatch runs after the decline commits, in its own transaction, so a failed
     * dispatch cannot undo the decline; it is logged and the dispatch-again job retries the order.
     */
    OfferResponse decline(long courierId, long offerId) {
        Declined declined =
                Objects.requireNonNull(transaction.execute(status -> declineAndRelease(courierId, offerId)));
        if (declined.changed()) {
            try {
                dispatch.dispatch(declined.offer().orderId());
            } catch (RuntimeException e) {
                log.warn(
                        "Dispatch of order {} after a decline failed; the dispatch-again job will retry it",
                        declined.offer().orderId(),
                        e);
            }
        }
        return declined.offer();
    }

    private record Declined(OfferResponse offer, boolean changed) {}

    private Declined declineAndRelease(long courierId, long offerId) {
        // The courier first, then the offer: the lock order dispatch's claim uses.
        Courier courier = couriers.lock(courierId);
        Optional<OfferResponse> declined = respond(courierId, offerId, OfferStatus.DECLINED);
        if (declined.isEmpty()) {
            return new Declined(repeatOrClosed(courierId, offerId, OfferStatus.DECLINED), false);
        }
        couriers.release(courier);
        return new Declined(declined.get(), true);
    }

    /** Closes the courier's offer as {@code answer} if it is still open and unexpired. */
    private Optional<OfferResponse> respond(long courierId, long offerId, OfferStatus answer) {
        return jdbc.sql("UPDATE offers SET status = :answer, responded_at = now()"
                        + " WHERE id = :id AND courier_id = :courierId AND status = 'OFFERED' AND expires_at > now()"
                        + " RETURNING id, order_id, status, responded_at")
                .param("answer", answer.name())
                .param("id", offerId)
                .param("courierId", courierId)
                .query(OfferService::offerResponse)
                .optional();
    }

    private record OfferState(String status, boolean open) {}

    /**
     * The answer when {@link #respond} matched nothing, or was skipped: the offer as it stands if it already has
     * {@code answer} (a repeat), otherwise 404 or 409. An offer that is still open gets here only when accept skipped
     * {@code respond} for a courier who is not BUSY.
     */
    private OfferResponse repeatOrClosed(long courierId, long offerId, OfferStatus answer) {
        OfferState state = jdbc.sql("SELECT status, status = 'OFFERED' AND expires_at > now() AS open"
                        + " FROM offers WHERE id = ? AND courier_id = ?")
                .params(offerId, courierId)
                .query((rs, row) -> new OfferState(rs.getString("status"), rs.getBoolean("open")))
                .optional()
                .orElseThrow(() -> new DomainException(
                        DomainException.Category.NOT_FOUND,
                        "OFFER_NOT_FOUND",
                        "Offer " + offerId + " does not exist for this courier."));
        String status = state.status();
        if (!status.equals(answer.name()) && state.open()) {
            throw new DomainException(
                    DomainException.Category.CONFLICT, "COURIER_NOT_BUSY", "Only a BUSY courier can accept an offer.");
        }
        if (!status.equals(answer.name())) {
            throw new DomainException(
                    DomainException.Category.CONFLICT, "OFFER_CLOSED", "The offer is no longer open.");
        }
        return jdbc.sql("SELECT id, order_id, status, responded_at FROM offers WHERE id = ?")
                .param(offerId)
                .query(OfferService::offerResponse)
                .single();
    }

    /** Maps an answered offer; {@code responded_at} is set once an offer leaves OFFERED. */
    private static OfferResponse offerResponse(ResultSet rs, int row) throws SQLException {
        return new OfferResponse(
                rs.getLong("id"),
                rs.getLong("order_id"),
                OfferStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("responded_at").toInstant());
    }
}
