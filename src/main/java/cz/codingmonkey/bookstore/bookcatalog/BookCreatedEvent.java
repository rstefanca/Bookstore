package cz.codingmonkey.bookstore.bookcatalog;

import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.Auditable;
import java.util.List;
import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Published by the book module after a book has been added to the catalog.
 *
 * @param authors     author names in credited order
 * @param genres      genre names, sorted by name; may be empty
 * @param description optional
 * @param revision    the book's version; increases with every change, so consumers can ignore out-of-order events
 * @param actor       account of whoever made the change; {@code null} without a signed-in user (startup,
 *                    background work)
 */
@DomainEvent
public record BookCreatedEvent(
        BookId bookId,
        String title,
        List<String> authors,
        List<String> genres,
        String isbn,
        @Nullable String description,
        long revision,
        @Nullable AccountId actor
) implements Auditable {

    public BookCreatedEvent {
        authors = List.copyOf(authors);
        genres = List.copyOf(genres);
    }

    @Override
    public String auditAction() {
        return "BOOK_CREATED";
    }

    @Override
    public BookId auditSubject() {
        return bookId;
    }

    @Override
    public String auditDescription() {
        return "Created \"%s\" by %s (ISBN %s)".formatted(title, String.join(", ", authors), isbn);
    }
}
