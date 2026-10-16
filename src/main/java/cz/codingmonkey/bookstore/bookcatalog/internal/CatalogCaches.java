package cz.codingmonkey.bookstore.bookcatalog.internal;

/**
 * Names of the catalog's caches. Both hold responses by id; responses include names of related authors and genres,
 * so renaming those evicts the affected caches too.
 */
final class CatalogCaches {

    /** {@link BookResponse} by {@link cz.codingmonkey.bookstore.bookcatalog.BookId}. */
    static final String BOOKS = "books";

    /** {@link AuthorResponse} by {@link cz.codingmonkey.bookstore.bookcatalog.AuthorId}. */
    static final String AUTHORS = "authors";

    private CatalogCaches() {
    }
}
