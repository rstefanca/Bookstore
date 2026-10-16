package cz.codingmonkey.bookstore.bookcatalog;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * Published by the book module after a book has been removed from the catalog.
 *
 * @param actor username of whoever made the change; {@code null} without a signed-in user (startup, background
 *              work)
 */
@DomainEvent
public record BookDeletedEvent(BookId bookId, String isbn, String actor) {
}
