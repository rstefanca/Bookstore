package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookCondition;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookOrder;
import cz.codingmonkey.bookstore.rating.RatingSummary;
import cz.codingmonkey.bookstore.rating.Ratings;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class RatingService implements Ratings {

    private static final ChangeLog changes = ChangeLog.of(RatingService.class);

    private final BookRatings ratings;
    private final BookCatalog books;

    RatingService(BookRatings ratings, BookCatalog books) {
        this.ratings = ratings;
        this.books = books;
    }

    @Override
    public RatingSummary summaryOf(BookId bookId) {
        return summariesOf(List.of(bookId)).get(bookId);
    }

    @Override
    public Map<BookId, RatingSummary> summariesOf(Collection<BookId> bookIds) {
        Map<BookId, Map<Integer, Integer>> counts = bookIds.isEmpty() ? Map.of() : ratings.countStars(bookIds);
        Map<BookId, RatingSummary> summaries = new HashMap<>(bookIds.size());
        for (BookId bookId : bookIds) {
            Map<Integer, Integer> stars = counts.get(bookId);
            summaries.put(bookId, stars == null ? RatingSummary.NONE : RatingSummary.of(stars));
        }
        return summaries;
    }

    @Override
    public BookCondition ratedAtLeast(BigDecimal minAverage) {
        return RatingRanking.atLeast(minAverage);
    }

    @Override
    public BookOrder byRating() {
        return RatingRanking.byScore(ratings.meanStars());
    }

    @Override
    public Map<BookId, Integer> ratingsBy(String customer) {
        return ratings.starsBy(customer);
    }

    @Override
    public List<BookId> topRated(int offset, int count) {
        return ratings.topRated(ratings.meanStars(), offset, count);
    }

    /**
     * Records the customer's rating of a book, replacing their earlier one. The book stays locked against deletion
     * until the transaction commits, so a concurrent delete can't leave a rating for a book that no longer exists.
     *
     * @throws UnknownBookException if there is no such book
     */
    @Transactional
    Rating rate(BookId bookId, String customer, int stars) {
        if (!books.existsAndLockAgainstDeletion(bookId)) {
            throw new UnknownBookException(bookId);
        }
        // The column has microsecond precision; return what is stored.
        Rating rating = ratings.save(bookId, customer, stars, Instant.now().truncatedTo(ChronoUnit.MICROS));
        changes.info("Customer {} rated book {} with {} stars", customer, bookId, stars);
        return rating;
    }

    Optional<Rating> find(BookId bookId, String customer) {
        return ratings.find(bookId, customer);
    }

    /** A deleted book's ratings go with it. */
    @ApplicationModuleListener
    void on(BookDeletedEvent event) {
        int removed = ratings.deleteAll(event.bookId());
        if (removed > 0) {
            changes.info("Removed {} ratings of deleted book {}", removed, event.bookId());
        }
    }
}
