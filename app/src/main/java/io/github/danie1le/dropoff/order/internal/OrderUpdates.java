package io.github.danie1le.dropoff.order.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Sends live messages to an order's STOMP topic, {@code /topic/orders/{id}} (M4-1, #3, #42). */
@Component
class OrderUpdates {

    private static final Logger log = LoggerFactory.getLogger(OrderUpdates.class);

    private final SimpMessageSendingOperations messaging;

    OrderUpdates(SimpMessageSendingOperations messaging) {
        this.messaging = messaging;
    }

    static String topic(long orderId) {
        return "/topic/orders/" + orderId;
    }

    /**
     * Sends {@code message} once the current transaction commits, so a status change that rolls back is never pushed.
     * A failed send is logged and dropped: the push is best-effort, and the order body stays the source of truth.
     */
    void statusAfterCommit(OrderStatusMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("status messages must be sent from inside a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    messaging.convertAndSend(topic(message.orderId()), message);
                } catch (RuntimeException ex) {
                    log.warn("STATUS message for order {} failed after commit", message.orderId(), ex);
                }
            }
        });
    }

    /**
     * Sends {@code message} now. Dispatch publishes the location only after it commits, so there is nothing here to
     * wait for. A failed send is logged and dropped, like a status: the next location follows within seconds.
     */
    void location(OrderLocationMessage message) {
        try {
            messaging.convertAndSend(topic(message.orderId()), message);
        } catch (RuntimeException ex) {
            log.warn("LOCATION message for order {} failed", message.orderId(), ex);
        }
    }
}
