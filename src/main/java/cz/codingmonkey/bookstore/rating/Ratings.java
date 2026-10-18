package cz.codingmonkey.bookstore.rating;

import cz.codingmonkey.bookstore.bookcatalog.BookCondition;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookOrder;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read access to book ratings for other modules.
 */
public interface Ratings {

    /** The ratings summary of one book; {@link RatingSummary#NONE} if nobody rated it. */
    RatingSummary summaryOf(BookId bookId);

    /**
     * Summaries of the given books; every requested book is in the map, unrated ones with
     * {@link RatingSummary#NONE}.
     */
    Map<BookId, RatingSummary> summariesOf(Collection<BookId> bookIds);

    /**
     * Books whose displayed average (one decimal, half up, as {@link RatingSummary#average()}) is at least
     * {@code minAverage}; unrated books never match.
     */
    BookCondition ratedAtLeast(BigDecimal minAverage);

    /**
     * Books by rank score, best first, unrated last. The score is a damped average: the book's stars plus five
     * ratings of the store-wide mean, so a few ratings count less than many. Reads that mean when called.
     */
    BookOrder byRating();

    /** The customer's stars per book; empty if they rated nothing. */
    Map<BookId, Integer> ratingsBy(AccountId customer);

    /**
     * Rated books by rank score (as {@link #byRating()}), best first, ties by book id; a window of {@code count}
     * starting at {@code offset}.
     */
    List<BookId> topRated(int offset, int count);
}
