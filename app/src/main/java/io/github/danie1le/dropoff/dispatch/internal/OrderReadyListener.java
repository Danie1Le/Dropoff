package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.contracts.OrderReady;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts dispatch when an order is ready (M3-2, ticket #42). Runs after the order's commit, through the Modulith event
 * registry, which retries an unfinished delivery on restart.
 *
 * <p>Not an {@code @ApplicationModuleListener}, whose single transaction would roll back the WAITING row whenever
 * dispatching fails. Here the row commits on its own first, so a failed dispatch is only logged and the dispatch-again
 * job retries it.
 */
@Component
class OrderReadyListener {

    private static final Logger log = LoggerFactory.getLogger(OrderReadyListener.class);

    private final DispatchService dispatch;

    OrderReadyListener(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @Async
    @TransactionalEventListener
    void on(OrderReady event) {
        dispatch.open(event);
        try {
            dispatch.dispatch(event.orderId());
        } catch (RuntimeException e) {
            log.warn("Dispatch of order {} failed; the dispatch-again job will retry it", event.orderId(), e);
        }
    }
}
