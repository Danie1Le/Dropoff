package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.contracts.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes {@code order_events} rows, the audit trail of every status change (ticket #10), and pushes each change as a
 * STOMP {@code STATUS} message after commit (M4-1). Every status change goes through here, so the two never disagree.
 */
@Component
class OrderEventLog {

    private final JdbcClient jdbc;
    private final OrderUpdates updates;

    OrderEventLog(JdbcClient jdbc, OrderUpdates updates) {
        this.jdbc = jdbc;
        this.updates = updates;
    }

    /** Records that the order moved from {@code from} (null for a new order) to {@code to}. */
    void record(long orderId, @Nullable String from, OrderStatus to, Actor actor, Instant at) {
        jdbc.sql("INSERT INTO order_events (order_id, from_status, to_status, actor, created_at)"
                        + " VALUES (:id, :from, :to, :actor, :at)")
                .param("id", orderId)
                .param("from", from)
                .param("to", to.name())
                .param("actor", actor.name())
                .param("at", Timestamp.from(at))
                .update();
        updates.statusAfterCommit(new OrderStatusMessage(orderId, to, actor, at));
    }
}
