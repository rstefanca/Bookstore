package cz.codingmonkey.bookstore.shared;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Logs data changes at INFO once they are committed, so the log never claims a change that was rolled back.
 * Outside a transaction it logs right away. Create one per class:
 * {@code private static final ChangeLog changes = ChangeLog.of(MyService.class);}
 */
public final class ChangeLog {

    private final Logger log;

    private ChangeLog(Logger log) {
        this.log = log;
    }

    /** Logs through the logger of the given class, so log lines show where the change was made. */
    public static ChangeLog of(Class<?> owner) {
        return new ChangeLog(LogManager.getLogger(owner));
    }

    /**
     * @param message a Log4j message with {@code {}} placeholders for the {@code params}
     */
    public void info(String message, @Nullable Object... params) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info(message, params);
                }
            });
        } else {
            log.info(message, params);
        }
    }
}
