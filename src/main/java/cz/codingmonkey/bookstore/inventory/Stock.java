package cz.codingmonkey.bookstore.inventory;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.Collection;
import java.util.Map;

/**
 * Read access to stock levels for other modules, e.g. to warn before ordering more than is in stock. Reading
 * reserves nothing: only a {@link StockReservationRequested} takes copies out of stock.
 */
public interface Stock {

    /** Copies in stock per book; every requested book is in the map, with 0 when it has no stock record. */
    Map<BookId, Integer> availableOf(Collection<BookId> bookIds);
}
