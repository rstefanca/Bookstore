package cz.codingmonkey.bookstore.payment;

import cz.codingmonkey.bookstore.shared.AccountId;
import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Asks to refund a charged payment (once). Answered with {@link PaymentRefunded}.
 *
 * @param actor account of whoever caused the refund; {@code null} without one
 */
@DomainEvent
public record RefundRequested(PaymentReference reference, @Nullable AccountId actor) {
}
