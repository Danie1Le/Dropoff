package io.github.danie1le.dropoff.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Work that must not happen for a change that rolls back, such as moving a counter (M6-1). */
public final class AfterCommit {

    private AfterCommit() {}

    /** Runs {@code action} once the current transaction commits, or now if there is no transaction. */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
