package cz.codingmonkey.bookstore.bookcatalog;

import cz.codingmonkey.bookstore.shared.LongIdType;

/**
 * Persists {@link AuthorId} as {@code BIGINT}. Referenced via {@code @Type(AuthorIdType.class)} by every entity that
 * stores an author id, including those in other modules.
 */
public class AuthorIdType extends LongIdType<AuthorId> {

    public AuthorIdType() {
        super(AuthorId.class, AuthorId::new);
    }
}
