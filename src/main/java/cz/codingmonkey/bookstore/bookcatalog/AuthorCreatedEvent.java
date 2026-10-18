package cz.codingmonkey.bookstore.bookcatalog;

import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.Auditable;
import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Published by the book module after an author has been added to the catalog.
 *
 * @param actor account of whoever made the change; {@code null} without a signed-in user
 */
@DomainEvent
public record AuthorCreatedEvent(AuthorId authorId, String name, @Nullable AccountId actor) implements Auditable {

    @Override
    public String auditAction() {
        return "AUTHOR_CREATED";
    }

    @Override
    public AuthorId auditSubject() {
        return authorId;
    }

    @Override
    public String auditDescription() {
        return "Created author \"%s\"".formatted(name);
    }
}
