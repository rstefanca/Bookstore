package cz.codingmonkey.bookstore.inventory;

import cz.codingmonkey.bookstore.shared.AccountId;
import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Asks the inventory to put a reservation's stock back (once; repeated requests change nothing).
 *
 * @param actor account of whoever caused the request; {@code null} without one
 */
@DomainEvent
public record StockReleaseRequested(ReservationId reservationId, @Nullable AccountId actor) {
}
