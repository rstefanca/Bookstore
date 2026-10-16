package cz.codingmonkey.bookstore.payment;

import org.jmolecules.event.annotation.DomainEvent;

/** The amount was charged. */
@DomainEvent
public record PaymentSucceeded(PaymentReference reference) {
}
