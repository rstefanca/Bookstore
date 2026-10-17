package cz.codingmonkey.bookstore.bookcatalog;

import cz.codingmonkey.bookstore.shared.Auditable;
import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Published by the book module after a book has been removed from the catalog.
 *
 * @param actor username of whoever made the change; {@code null} without a signed-in user (startup, background
 *              work)
 */
@DomainEvent
public record BookDeletedEvent(BookId bookId, String isbn, @Nullable String actor) implements Auditable {

    @Override
    public String auditAction() {
        return "BOOK_DELETED";
    }

    @Override
    public BookId auditSubject() {
        return bookId;
    }

    @Override
    public String auditDescription() {
        return "Deleted book with ISBN %s".formatted(isbn);
    }
}
