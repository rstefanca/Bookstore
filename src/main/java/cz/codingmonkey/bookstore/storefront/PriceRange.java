package cz.codingmonkey.bookstore.storefront;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Optional price bounds, inclusive, in the store currency.
 */
record PriceRange(@Nullable BigDecimal min, @Nullable BigDecimal max) {

    static final PriceRange ANY = new PriceRange(null, null);

    PriceRange {
        if (min != null && min.signum() < 0 || max != null && max.signum() < 0) {
            throw new InvalidPriceRangeException("Prices can't be negative");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new InvalidPriceRangeException(
                    "minPrice (%s) can't be above maxPrice (%s)".formatted(min.toPlainString(), max.toPlainString()));
        }
    }

    boolean isAny() {
        return min == null && max == null;
    }
}
