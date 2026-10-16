package cz.codingmonkey.bookstore.bookcatalog;

/**
 * Filters for listing books. Every filter is optional ({@code null}); given filters combine with AND.
 *
 * @param title      the title contains this text, ignoring case
 * @param authorName an author's name contains this text, ignoring case
 * @param authorId   the book is by this author
 * @param genreId    the book is in this genre
 * @param condition  the book matches another module's condition, e.g. pricing's price range
 */
public record BookSearch(String title, String authorName, AuthorId authorId, GenreId genreId,
        BookCondition condition) {

    /** A search without another module's condition. */
    public BookSearch(String title, String authorName, AuthorId authorId, GenreId genreId) {
        this(title, authorName, authorId, genreId, null);
    }

    /** The same search, limited to books matching the condition. */
    public BookSearch matching(BookCondition condition) {
        return new BookSearch(title, authorName, authorId, genreId, condition);
    }
}
