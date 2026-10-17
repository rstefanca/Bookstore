package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.List;
import java.util.Optional;

/**
 * Similar-book recommendations for other modules. Knows books only by id; whoever shows them adds the details.
 */
public interface Recommendations {

    /**
     * The books most similar to the given one, most similar first, never the book itself. Can be fewer than
     * {@code limit}: there may not be that many other books, and the approximate (HNSW) index search looks at a
     * bounded number of candidates (pgvector's {@code hnsw.ef_search}, 40 by default).
     *
     * @return empty if there are no recommendations for the book: it doesn't exist, or it was created moments ago
     *         and hasn't been indexed yet
     */
    Optional<List<Recommendation>> similarTo(BookId bookId, int limit);
}
