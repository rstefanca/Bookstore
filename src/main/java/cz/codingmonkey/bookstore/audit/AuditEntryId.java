package cz.codingmonkey.bookstore.audit;

import com.fasterxml.jackson.annotation.JsonValue;
import cz.codingmonkey.bookstore.shared.LongId;

record AuditEntryId(@JsonValue long value) implements LongId {

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
