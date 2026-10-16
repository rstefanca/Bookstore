package cz.codingmonkey.bookstore.audit;

import com.fasterxml.jackson.annotation.JsonValue;

record AuditEntryId(@JsonValue long value) {

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
