package cz.codingmonkey.bookstore.inventory;

import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Asks the inventory to put a reservation's stock back (once; repeated requests change nothing).
 *
 * @param actor username of whoever caused the request; {@code null} without one
 */
@DomainEvent
public record StockReleaseRequested(ReservationId reservationId, @Nullable String actor) {
}
