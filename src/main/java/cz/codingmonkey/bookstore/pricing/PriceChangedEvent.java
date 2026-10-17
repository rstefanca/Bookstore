package cz.codingmonkey.bookstore.pricing;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.shared.Auditable;
import org.jmolecules.event.annotation.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Published by the pricing module when a book's price is set, changed or removed.
 *
 * @param oldPrice {@code null} if the book had no price
 * @param newPrice {@code null} if the price was removed (the book is no longer for sale)
 * @param actor    username of whoever made the change; when a deleted book loses its price, whoever deleted the
 *                 book; {@code null} without a signed-in user
 */
@DomainEvent
public record PriceChangedEvent(
        BookId bookId,
        @Nullable Money oldPrice,
        @Nullable Money newPrice,
        @Nullable String actor
) implements Auditable {

    @Override
    public String auditAction() {
        return "PRICE_CHANGED";
    }

    @Override
    public BookId auditSubject() {
        return bookId;
    }

    @Override
    public String auditDescription() {
        if (oldPrice == null) {
            return "Price set to %s".formatted(newPrice);
        }
        if (newPrice == null) {
            return "Price removed (was %s); no longer for sale".formatted(oldPrice);
        }
        return "Price changed from %s to %s".formatted(oldPrice, newPrice);
    }
}
