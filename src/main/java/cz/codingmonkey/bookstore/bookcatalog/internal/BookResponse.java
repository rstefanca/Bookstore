package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * @param authors in credited order
 * @param genres  sorted by name
 */
public record BookResponse(BookId id, String title, List<AuthorSummary> authors, List<GenreResponse> genres,
        String isbn, Integer publishedYear, String description) {

    /** An author as listed on a book; see {@link AuthorResponse} for the full author. */
    public record AuthorSummary(AuthorId id, String name) {

        static AuthorSummary from(Author author) {
            return new AuthorSummary(author.getId(), author.getName());
        }
    }

    /**
     * @param authors must contain every author of the book
     * @param genres  must contain every genre of the book
     */
    static BookResponse from(Book book, Map<AuthorId, Author> authors, Map<GenreId, Genre> genres) {
        List<AuthorSummary> bookAuthors = book.getAuthorIds().stream()
                .map(authors::get)
                .map(AuthorSummary::from)
                .toList();
        List<GenreResponse> bookGenres = book.getGenreIds().stream()
                .map(genres::get)
                .sorted(Comparator.comparing(Genre::getName))
                .map(GenreResponse::from)
                .toList();
        return new BookResponse(book.getId(), book.getTitle(), bookAuthors, bookGenres, book.getIsbn(),
                book.getPublishedYear(), book.getDescription());
    }
}
