package cz.codingmonkey.bookstore.storefront;

import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * How a list of books is sorted: by title, or by rating (best first, unrated last, ties by title).
 */
enum BookOrdering {

    TITLE, RATING;

    /** Parses the {@code orderBy} value, ignoring case. */
    static BookOrdering parse(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidOrderingException("orderBy must be title or rating, was " + value);
        }
    }

    /**
     * The page as the catalog should get it: sorted by title, or unsorted for the rating order, which the search
     * carries and the catalog applies instead of the page's sort.
     */
    Pageable pageOf(Pageable page) {
        Sort sort = this == TITLE ? Sort.by("title") : Sort.unsorted();
        return PageRequest.of(page.getPageNumber(), page.getPageSize(), sort);
    }
}
