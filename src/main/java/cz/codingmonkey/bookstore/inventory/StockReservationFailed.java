package cz.codingmonkey.bookstore.inventory;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.List;
import org.jmolecules.event.annotation.DomainEvent;

/**
 * Not enough stock for at least one line; nothing was taken.
 *
 * @param missingBooks the books without enough stock
 */
@DomainEvent
public record StockReservationFailed(ReservationId reservationId, String reason, List<BookId> missingBooks) {

    public StockReservationFailed {
        missingBooks = List.copyOf(missingBooks);
    }
}
