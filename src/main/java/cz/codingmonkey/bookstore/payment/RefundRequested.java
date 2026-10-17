package cz.codingmonkey.bookstore.payment;

import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Asks to refund a charged payment (once). Answered with {@link PaymentRefunded}.
 *
 * @param actor username of whoever caused the refund; {@code null} without one
 */
@DomainEvent
public record RefundRequested(PaymentReference reference, @Nullable String actor) {
}
