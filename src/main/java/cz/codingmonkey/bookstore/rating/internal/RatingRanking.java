package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookCondition;
import cz.codingmonkey.bookstore.bookcatalog.BookOrder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The rating filter and order other modules apply to the catalog's book search, as subqueries on {@link RatedBook}
 * correlated with the catalog's book id.
 */
final class RatingRanking {

    /**
     * How many ratings of the store-wide mean a book's rank score starts with:
     * {@code score = (PRIOR_WEIGHT × mean + sum of stars) / (PRIOR_WEIGHT + number of ratings)}.
     */
    static final int PRIOR_WEIGHT = 5;

    private RatingRanking() {
    }

    /**
     * Books whose average rounded half up to one decimal is at least {@code min}; unrated books never match.
     * <p>
     * With {@code k = 2 × ceil(10 × min) − 1}, that is {@code 20 × sum >= k × count}: the rounded average is at
     * least {@code ceil(10 × min) / 10} exactly when the average is at least {@code k / 20}. Integers only, so
     * there is no floating-point error at the rounding boundary (an average of 4.05 shows as 4.1).
     */
    static BookCondition atLeast(BigDecimal min) {
        BigDecimal k = min.scaleByPowerOfTen(1).setScale(0, RoundingMode.CEILING)
                .multiply(BigDecimal.TWO).subtract(BigDecimal.ONE);
        // EXISTS (select 1 from RatedBook r where r.bookId = <book> group by r.bookId
        //         having 20 * sum(r.stars) >= k * count(r))
        return (bookId, query, cb) -> {
            Subquery<Integer> rated = query.subquery(Integer.class);
            Root<RatedBook> rating = rated.from(RatedBook.class);
            Expression<Number> sum = cb.sum(rating.get("stars"));
            rated.select(cb.literal(1))
                    .where(cb.equal(rating.get("bookId"), bookId))
                    .groupBy(rating.get("bookId"))
                    .having(cb.ge(cb.prod(20, sum), cb.prod(k, cb.count(rating))));
            return cb.exists(rated);
        };
    }

    /**
     * Books by rank score, best first, unrated books (no score) last.
     *
     * @param mean the mean of all ratings in the store; any value when there are none, as every score is then null
     */
    static BookOrder byScore(double mean) {
        // (select (C * mean + sum(r.stars)) / (C + count(r)) from RatedBook r where r.bookId = <book>)
        // desc nulls last. The double literals make the arithmetic floating point (as() only types the expression
        // in Java). sum is null for an unrated book, and so is its score.
        return (bookId, query, cb) -> {
            Subquery<Double> score = query.subquery(Double.class);
            Root<RatedBook> rating = score.from(RatedBook.class);
            Expression<Double> stars = cb.sum(rating.get("stars").as(Double.class));
            Expression<Double> ratings = cb.count(rating).as(Double.class);
            Expression<Number> damped = cb.quot(cb.sum(PRIOR_WEIGHT * mean, stars),
                    cb.sum((double) PRIOR_WEIGHT, ratings));
            score.select(damped.as(Double.class)).where(cb.equal(rating.get("bookId"), bookId));
            return cb.desc(score, Nulls.LAST);
        };
    }
}
