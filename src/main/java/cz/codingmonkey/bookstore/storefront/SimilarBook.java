package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.bookcatalog.BookDetails.AuthorSummary;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails.GenreSummary;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.rating.RatingSummary;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A recommended book: the same fields as a book in the storefront, plus how similar it is. Only books for sale are
 * recommended, so the price is always set.
 *
 * @param authors    in credited order
 * @param genres     sorted by name
 * @param rating     average, count and per-star counts of customers' ratings; never null, {@code count} 0 if unrated
 * @param similarity cosine similarity to the book the recommendations are for, from -1 to 1 (higher is more
 *                   similar; above about 0.6 usually means a shared topic)
 */
record SimilarBook(
        BookId id,
        String title,
        List<AuthorSummary> authors,
        List<GenreSummary> genres,
        String isbn,
        @Nullable Integer publishedYear,
        @Nullable String description,
        Money price,
        RatingSummary rating,
        double similarity
) {

    static SimilarBook of(BookDetails book, Money price, RatingSummary rating, double similarity) {
        return new SimilarBook(book.id(), book.title(), book.authors(), book.genres(), book.isbn(),
                book.publishedYear(), book.description(), price, rating, similarity);
    }
}
