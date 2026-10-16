package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.shared.LongIdType;

class AuditEntryIdType extends LongIdType<AuditEntryId> {

    AuditEntryIdType() {
        super(AuditEntryId.class, AuditEntryId::new, AuditEntryId::value);
    }
}
