package cz.codingmonkey.bookstore.payment;

import org.jmolecules.event.annotation.DomainEvent;

/** A charged payment was refunded. */
@DomainEvent
public record PaymentRefunded(PaymentReference reference) {
}
