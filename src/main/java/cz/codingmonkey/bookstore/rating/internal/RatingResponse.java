package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.time.Instant;

/**
 * The signed-in customer's rating of a book.
 *
 * @param bookId  the rated book
 * @param stars   the rating, 1 (worst) to 5 (best)
 * @param ratedAt when the customer last rated this book
 */
record RatingResponse(BookId bookId, int stars, Instant ratedAt) {

    static RatingResponse of(Rating rating) {
        return new RatingResponse(rating.bookId(), rating.stars(), rating.ratedAt());
    }
}
