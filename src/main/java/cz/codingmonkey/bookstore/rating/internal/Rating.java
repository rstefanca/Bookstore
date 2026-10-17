package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.time.Instant;

/** One customer's rating of a book. */
record Rating(BookId bookId, int stars, Instant ratedAt) {
}
