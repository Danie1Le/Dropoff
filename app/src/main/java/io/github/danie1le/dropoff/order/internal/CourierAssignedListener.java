package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.CourierAssigned;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records the courier on the order once dispatch assigns one (M3-3, ticket #11, point 4). Runs after dispatch's
 * commit, through the Modulith event registry, which retries an unfinished delivery (ADR-0006).
 *
 * <p>Only a READY order takes a courier: a cancelled order, or one already past READY, is left alone. Writing the same
 * courier again is harmless, so a redelivered event changes nothing.
 */
@Component
class CourierAssignedListener {

    private final JdbcClient jdbc;
    private final Clock clock;

    CourierAssignedListener(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(CourierAssigned event) {
        jdbc.sql("UPDATE orders SET courier_id = :courierId, updated_at = :now WHERE id = :id AND status = 'READY'")
                .param("courierId", event.courierId())
                .param("now", Timestamp.from(DbClock.now(clock)))
                .param("id", event.orderId())
                .update();
    }
}
