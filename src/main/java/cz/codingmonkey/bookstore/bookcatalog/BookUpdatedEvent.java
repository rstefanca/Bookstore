package cz.codingmonkey.bookstore.bookcatalog;

import java.util.List;
import org.jmolecules.event.annotation.DomainEvent;

/**
 * Published by the book module after a book has been changed. Carries the book's full new state.
 *
 * @param authors     author names in credited order
 * @param genres      genre names, sorted by name; may be empty
 * @param description optional
 * @param revision    the book's version; increases with every change, so consumers can ignore out-of-order events
 */
@DomainEvent
public record BookUpdatedEvent(BookId bookId, String title, List<String> authors, List<String> genres, String isbn,
        String description, long revision) {

    public BookUpdatedEvent {
        authors = List.copyOf(authors);
        genres = List.copyOf(genres);
    }
}
