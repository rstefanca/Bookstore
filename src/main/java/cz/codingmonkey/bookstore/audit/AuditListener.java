package cz.codingmonkey.bookstore.audit;

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
     * An event delivered again (retry after a crash) is skipped when an entry with the same subject, action and
     * time exists. That only works for events that set their own {@code occurredAt}. The log line names only the
     * action and the subject: the actor identifies a person and the description may carry book titles.
     */
    @ApplicationModuleListener
    void on(Auditable event) {
        var entry = AuditEntry.of(event.auditAction(), event.auditSubject(), event.actor(),
                event.auditDescription(), event.occurredAt());
        if (repository.existsBySubjectAndActionAndOccurredAt(entry.getSubject(), entry.getAction(),
                entry.getOccurredAt())) {
            return; // the same event delivered again
        }
        repository.save(entry);
        changes.info("Recorded audit entry {} for {}", entry.getAction(), entry.getSubject());
    }
}
