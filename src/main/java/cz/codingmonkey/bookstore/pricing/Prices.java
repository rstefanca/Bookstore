package cz.codingmonkey.bookstore.pricing;

import cz.codingmonkey.bookstore.bookcatalog.BookCondition;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Read access to current book prices for other modules. A book without a price is not for sale.
 */
public interface Prices {

    Optional<Money> priceOf(BookId bookId);

    /** Prices of the given books; books without a price are left out. */
    Map<BookId, Money> pricesOf(Collection<BookId> bookIds);

    /**
     * A catalog search condition matching books whose price is within the range, bounds included, in the store
     * currency. Books without a price never match. The database evaluates it as part of the catalog's query.
     *
     * @param min optional lower bound
     * @param max optional upper bound
     */
    BookCondition pricedBetween(BigDecimal min, BigDecimal max);
}
