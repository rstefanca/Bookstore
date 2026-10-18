package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores the star ratings in {@code book_rating}, one row per book and customer.
 */
@Repository
class BookRatings {

    private final JdbcClient jdbc;

    BookRatings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the customer's rating of the book, or replaces the stars of the existing one. */
    Rating save(BookId bookId, AccountId customer, int stars, Instant ratedAt) {
        jdbc.sql("""
                INSERT INTO book_rating (book_id, customer_id, stars, rated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (book_id, customer_id) DO UPDATE
                    SET stars = EXCLUDED.stars, rated_at = EXCLUDED.rated_at
                """)
                .param(bookId.value())
                .param(customer.value())
                .param(stars)
                .param(OffsetDateTime.ofInstant(ratedAt, ZoneOffset.UTC))
                .update();
        return new Rating(bookId, stars, ratedAt);
    }

    Optional<Rating> find(BookId bookId, AccountId customer) {
        return jdbc.sql("SELECT stars, rated_at FROM book_rating WHERE book_id = ? AND customer_id = ?")
                .param(bookId.value())
                .param(customer.value())
                .query((rs, row) -> new Rating(bookId, rs.getInt("stars"),
                        rs.getObject("rated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Number of ratings per star value of each of the books; books without ratings are left out. */
    Map<BookId, Map<Integer, Integer>> countStars(Collection<BookId> bookIds) {
        Long[] ids = bookIds.stream().map(BookId::value).toArray(Long[]::new);
        Map<BookId, Map<Integer, Integer>> counts = new HashMap<>();
        jdbc.sql("""
                SELECT book_id, stars, count(*) AS n FROM book_rating
                WHERE book_id = ANY(?)
                GROUP BY book_id, stars
                """)
                .param(ids)
                .query(rs -> {
                    counts.computeIfAbsent(new BookId(rs.getLong("book_id")), id -> new HashMap<>())
                            .put(rs.getInt("stars"), rs.getInt("n"));
                });
        return counts;
    }

    /** The mean of all ratings in the store; 0 when there are none. */
    double meanStars() {
        return jdbc.sql("SELECT coalesce(avg(stars), 0) FROM book_rating").query(Double.class).single();
    }

    /** The customer's stars per book; empty if they rated nothing. */
    Map<BookId, Integer> starsBy(AccountId customer) {
        Map<BookId, Integer> stars = new HashMap<>();
        jdbc.sql("SELECT book_id, stars FROM book_rating WHERE customer_id = ?")
                .param(customer.value())
                .query(rs -> {
                    stars.put(new BookId(rs.getLong("book_id")), rs.getInt("stars"));
                });
        return stars;
    }

    /**
     * Rated books by rank score ({@link RatingRanking}), best first, ties by book id.
     *
     * @param mean   the mean of all ratings in the store
     * @param offset how many of the best books to skip
     * @param count  how many books to return at most
     */
    List<BookId> topRated(double mean, int offset, int count) {
        return jdbc.sql("""
                SELECT book_id FROM book_rating
                GROUP BY book_id
                ORDER BY (:prior * :mean + sum(stars))::float8 / (:prior + count(*)) DESC, book_id
                OFFSET :offset LIMIT :count
                """)
                .param("prior", RatingRanking.PRIOR_WEIGHT)
                .param("mean", mean)
                .param("offset", offset)
                .param("count", count)
                .query((rs, row) -> new BookId(rs.getLong("book_id")))
                .list();
    }

    /** @return number of ratings removed */
    int deleteAll(BookId bookId) {
        return jdbc.sql("DELETE FROM book_rating WHERE book_id = ?").param(bookId.value()).update();
    }
}
