package cz.codingmonkey.bookstore.rating.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A customer's rating of a book.
 *
 * @param stars the rating, 1 (worst) to 5 (best)
 */
record RatingRequest(@Schema(example = "4") @NotNull @Min(1) @Max(5) Integer stars) {
}
