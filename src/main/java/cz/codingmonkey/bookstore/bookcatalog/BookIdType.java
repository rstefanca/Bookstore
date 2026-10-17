package cz.codingmonkey.bookstore.bookcatalog;

import cz.codingmonkey.bookstore.shared.LongIdType;

/**
 * Persists {@link BookId} as {@code BIGINT}. Referenced via {@code @Type(BookIdType.class)} by every entity that
 * stores a book id, including those in other modules.
 */
public class BookIdType extends LongIdType<BookId> {

    public BookIdType() {
        super(BookId.class, BookId::new);
    }
}
