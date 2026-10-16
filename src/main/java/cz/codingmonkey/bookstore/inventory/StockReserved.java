package cz.codingmonkey.bookstore.inventory;

import org.jmolecules.event.annotation.DomainEvent;

/** All lines of the reservation were taken from stock. */
@DomainEvent
public record StockReserved(ReservationId reservationId) {
}
