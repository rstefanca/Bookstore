package cz.codingmonkey.bookstore.inventory;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.List;
import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Asks the inventory to take stock for all lines, or none. Answered with {@link StockReserved} or
 * {@link StockReservationFailed}. Asking again for the same reservation repeats the earlier answer.
 *
 * @param actor username of whoever caused the request; {@code null} without one
 */
@DomainEvent
public record StockReservationRequested(ReservationId reservationId, List<Line> lines, @Nullable String actor) {

    public StockReservationRequested {
        lines = List.copyOf(lines);
    }

    public record Line(BookId bookId, int quantity) {
    }
}
