package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.audit.AuditEntry.Action;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.shared.Auditable;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records every {@link Auditable} event asynchronously after the publishing transaction commits. Publications are
 * tracked in the event publication registry, so events are not lost if a listener fails.
 */
@Component
class AuditListener {

    private static final ChangeLog changes = ChangeLog.of(AuditListener.class);

    private final AuditEntryRepository repository;

    AuditListener(AuditEntryRepository repository) {
        this.repository = repository;
    }

    /**
     * Each step of a purchase happens once per order, so an order step delivered again (retry after a crash) is
     * skipped; the database enforces it too.
     */
    @ApplicationModuleListener
    void on(Auditable event) {
        Action action = Action.valueOf(event.auditAction());
        if (event.auditSubject() instanceof OrderId orderId && repository.existsByOrderIdAndAction(orderId, action)) {
            return;
        }
        record(AuditEntry.of(action, event.auditSubject(), event.actor(), event.auditDescription(),
                event.occurredAt()));
    }

    private void record(AuditEntry entry) {
        repository.save(entry);
        if (entry.getOrderId() != null) {
            // Ids only: order entries carry the customer's username and book titles.
            changes.info("Recorded audit entry {} for order {}", entry.getAction(), entry.getOrderId());
            return;
        }
        changes.info("Recorded audit entry {} for {} {} by {}: {}", entry.getAction(),
                entry.getBookId() != null ? "book" : "author",
                entry.getBookId() != null ? entry.getBookId() : entry.getAuthorId(),
                entry.getActor() != null ? entry.getActor() : "system", entry.getDescription());
    }
}
