package cz.codingmonkey.bookstore.rating;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.Collection;
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
}
