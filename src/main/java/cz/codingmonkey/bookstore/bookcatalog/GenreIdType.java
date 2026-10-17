package cz.codingmonkey.bookstore.bookcatalog;

import cz.codingmonkey.bookstore.shared.LongIdType;

/**
 * Persists {@link GenreId} as {@code BIGINT}. Referenced via {@code @Type(GenreIdType.class)}.
 */
public class GenreIdType extends LongIdType<GenreId> {

    public GenreIdType() {
        super(GenreId.class, GenreId::new);
    }
}
