package cz.codingmonkey.bookstore.pricing;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import org.jmolecules.event.annotation.DomainEvent;

/**
 * Published by the pricing module when a book's price is set, changed or removed.
 *
 * @param title    the book's title at the time of the change, for the audit log; {@code null} if unknown (the book
 *                 was deleted meanwhile, or the event was published before it was added)
 * @param oldPrice {@code null} if the book had no price
 * @param newPrice {@code null} if the price was removed (the book is no longer for sale)
 * @param actor    username of whoever made the change; when a deleted book loses its price, whoever deleted the
 *                 book; {@code null} without a signed-in user
 */
@DomainEvent
public record PriceChangedEvent(BookId bookId, String title, Money oldPrice, Money newPrice, String actor) {
}
