package cz.codingmonkey.bookstore.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How customers rated a book.
 *
 * @param average mean stars rounded to one decimal (half up), {@code null} when nobody rated the book
 * @param count   number of ratings
 * @param stars   number of ratings per star value; always has the keys 1 to 5 in ascending order
 */
public record RatingSummary(@Nullable BigDecimal average, int count, Map<Integer, Integer> stars) {

    public static final int MIN_STARS = 1;
    public static final int MAX_STARS = 5;

    /** A book nobody has rated. */
    public static final RatingSummary NONE = of(Map.of());

    /**
     * Builds a summary from the number of ratings per star value.
     *
     * @param countsByStars ratings per star value; star values without ratings may be missing
     */
    public static RatingSummary of(Map<Integer, Integer> countsByStars) {
        Map<Integer, Integer> stars = new LinkedHashMap<>();
        int count = 0;
        long total = 0;
        for (int star = MIN_STARS; star <= MAX_STARS; star++) {
            int n = countsByStars.getOrDefault(star, 0);
            stars.put(star, n);
            count += n;
            total += (long) star * n;
        }
        BigDecimal average = count == 0
                ? null
                : BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
        return new RatingSummary(average, count, Collections.unmodifiableMap(stars));
    }
}
