package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.rating.RatingSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A customer's rating of a book.
 *
 * @param stars the rating, 1 (worst) to 5 (best)
 */
record RatingRequest(@Schema(example = "4") @NotNull @Min(RatingSummary.MIN_STARS) @Max(RatingSummary.MAX_STARS) Integer stars) {
}
