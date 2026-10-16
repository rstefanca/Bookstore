package cz.codingmonkey.bookstore.payment;

import org.jmolecules.event.annotation.DomainEvent;

/** The charge was declined; nothing was charged. */
@DomainEvent
public record PaymentFailed(PaymentReference reference, String reason) {
}
