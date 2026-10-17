package cz.codingmonkey.bookstore.inventory;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import org.jmolecules.event.annotation.DomainEvent;

/**
 * Published by the inventory module after a book's stock level changed.
 *
 * @param title       the book's title at the time of the change, for the audit log; {@code null} if unknown (the
 *                    book was deleted meanwhile, or the event was published before it was added)
 * @param delta       positive for a restock, negative for a withdrawal
 * @param newQuantity stock level after the adjustment
 * @param actor       username of whoever made the change; {@code null} without a signed-in user (startup,
 *                    background work)
 */
@DomainEvent
public record StockAdjustedEvent(BookId bookId, String title, int delta, int newQuantity, String actor) {

    public boolean isOutOfStock() {
        return newQuantity == 0;
    }
}
