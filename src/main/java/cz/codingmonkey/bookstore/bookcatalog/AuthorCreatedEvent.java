package cz.codingmonkey.bookstore.bookcatalog;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * Published by the book module after an author has been added to the catalog.
 *
 * @param actor username of whoever made the change; {@code null} without a signed-in user
 */
@DomainEvent
public record AuthorCreatedEvent(AuthorId authorId, String name, String actor) {
}
