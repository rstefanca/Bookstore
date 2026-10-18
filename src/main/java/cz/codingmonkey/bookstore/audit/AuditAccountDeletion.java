package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.security.AccountDeletionPolicy;
import cz.codingmonkey.bookstore.shared.AccountId;
import org.springframework.stereotype.Component;

/** Audit entries are kept for good; they refer to their actor by account id, so there is nothing to anonymize. */
@Component
class AuditAccountDeletion implements AccountDeletionPolicy {

    private final AuditEntryRepository entries;

    AuditAccountDeletion(AuditEntryRepository entries) {
        this.entries = entries;
    }

    @Override
    public boolean holdsRecords(AccountId account) {
        return entries.existsByActorId(account);
    }

    @Override
    public void removePersonalData(AccountId account) {
    }

    @Override
    public void anonymize(AccountId account) {
    }
}
