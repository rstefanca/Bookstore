package cz.codingmonkey.bookstore.bookcatalog;

import java.util.List;

/**
 * A book as other modules see it.
 *
 * @param authors     in credited order
 * @param genres      sorted by name
 * @param description optional
 */
public record BookDetails(BookId id, String title, List<AuthorSummary> authors, List<GenreSummary> genres,
        String isbn, Integer publishedYear, String description) {

    public record AuthorSummary(AuthorId id, String name) {
    }

    public record GenreSummary(GenreId id, String name) {
    }
}
