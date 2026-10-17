package cz.codingmonkey.bookstore.bookcatalog;

import org.jspecify.annotations.Nullable;

/**
 * Filters for listing books. Every filter is optional ({@code null}); given filters combine with AND.
 *
 * @param title      the title contains this text, ignoring case
 * @param authorName an author's name contains this text, ignoring case
 * @param authorId   the book is by this author
 * @param genreId    the book is in this genre
 * @param condition  the book matches another module's condition, e.g. pricing's price range
 * @param order      sorts the books by another module's data, before the catalog's own title and id order
 */
public record BookSearch(@Nullable String title, @Nullable String authorName, @Nullable AuthorId authorId,
        @Nullable GenreId genreId, @Nullable BookCondition condition, @Nullable BookOrder order) {

    /** A search without another module's condition. */
    public BookSearch(@Nullable String title, @Nullable String authorName, @Nullable AuthorId authorId,
            @Nullable GenreId genreId) {
        this(title, authorName, authorId, genreId, null, null);
    }

    /** The same search, also limited to books matching the condition; with one already set, both must hold. */
    public BookSearch matching(BookCondition added) {
        BookCondition existing = condition;
        BookCondition combined = existing == null ? added
                : (bookId, query, cb) -> cb.and(existing.toPredicate(bookId, query, cb),
                        added.toPredicate(bookId, query, cb));
        return new BookSearch(title, authorName, authorId, genreId, combined, order);
    }

    /** The same search, sorted by the order first; the catalog's title and id order breaks ties. */
    public BookSearch ordered(BookOrder order) {
        return new BookSearch(title, authorName, authorId, genreId, condition, order);
    }
}
