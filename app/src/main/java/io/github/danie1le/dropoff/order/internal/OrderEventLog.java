package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.contracts.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Writes {@code order_events} rows, the audit trail of every status change (ticket #10). */
final class OrderEventLog {

    private OrderEventLog() {}

    /** Records that the order moved from {@code from} (null for a new order) to {@code to}. */
    static void record(JdbcClient jdbc, long orderId, @Nullable String from, OrderStatus to, Actor actor, Instant at) {
        jdbc.sql("INSERT INTO order_events (order_id, from_status, to_status, actor, created_at)"
                        + " VALUES (:id, :from, :to, :actor, :at)")
                .param("id", orderId)
                .param("from", from)
                .param("to", to.name())
                .param("actor", actor.name())
                .param("at", Timestamp.from(at))
                .update();
    }
}
