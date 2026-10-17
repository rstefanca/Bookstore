package cz.codingmonkey.bookstore.payment;

import cz.codingmonkey.bookstore.pricing.Money;
import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Asks for a charge. Answered with {@link PaymentSucceeded} or {@link PaymentFailed}; asking again for the same
 * reference repeats the earlier answer.
 *
 * @param actor username of whoever pays; {@code null} without one
 */
@DomainEvent
public record PaymentRequested(PaymentReference reference, Money amount, @Nullable String actor) {
}
