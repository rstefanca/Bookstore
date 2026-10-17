package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.ordering.OrderId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Locale;

/**
 * What an audit entry is about. A new kind of subject is a new {@link Type} and factory; no migration needed.
 */
@Embeddable
record AuditSubject(
        @Enumerated(EnumType.STRING) @Column(name = "subject_type", nullable = false, length = 30) Type type,
        @Column(name = "subject_id", nullable = false) long id) {

    enum Type {
        BOOK, AUTHOR, ORDER
    }

    static AuditSubject of(BookId book) {
        return new AuditSubject(Type.BOOK, book.value());
    }

    static AuditSubject of(AuthorId author) {
        return new AuditSubject(Type.AUTHOR, author.value());
    }

    static AuditSubject of(OrderId order) {
        return new AuditSubject(Type.ORDER, order.value());
    }

    /** "book 2", "order 52": as in the change log. */
    @Override
    public String toString() {
        return type.name().toLowerCase(Locale.ROOT) + " " + id;
    }
}
