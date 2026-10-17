package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.shared.LongId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Locale;

/** What an audit entry is about: any LongId, kept as its type name and value. */
@Embeddable
record AuditSubject(
        @Column(name = "subject_type", nullable = false, length = 50) String typeName,
        @Column(name = "subject_id", nullable = false) long value) implements LongId {

    static AuditSubject of(LongId id) {
        return new AuditSubject(id.typeName(), id.value());
    }

    /** "book 2", "order 52". */
    @Override
    public String toString() {
        return typeName.toLowerCase(Locale.ROOT) + " " + value;
    }
}
