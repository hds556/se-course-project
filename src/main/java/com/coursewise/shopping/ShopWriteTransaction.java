package com.coursewise.shopping;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes seller catalog changes and order writes through commit on single-instance SQLite. */
@Component
public class ShopWriteTransaction {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final TransactionTemplate transaction;

    public ShopWriteTransaction(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
    }

    public <T> T execute(Supplier<T> action) {
        lock.lock();
        try {
            return transaction.execute(status -> action.get());
        } finally {
            lock.unlock();
        }
    }
}
