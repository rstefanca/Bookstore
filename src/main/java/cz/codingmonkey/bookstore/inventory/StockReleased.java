package cz.codingmonkey.bookstore.inventory;

import org.jmolecules.event.annotation.DomainEvent;

/** A reservation's stock was put back (announced once per reservation). */
@DomainEvent
public record StockReleased(ReservationId reservationId) {
}
