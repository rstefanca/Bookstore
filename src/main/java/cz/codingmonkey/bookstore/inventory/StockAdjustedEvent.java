package cz.codingmonkey.bookstore.inventory;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import org.jmolecules.event.annotation.DomainEvent;

/**
 * Published by the inventory module after a book's stock level changed.
 *
 * @param delta       positive for a restock, negative for a withdrawal
 * @param newQuantity stock level after the adjustment
 * @param actor       username of whoever made the change; {@code null} without a signed-in user (startup,
 *                    background work)
 */
@DomainEvent
public record StockAdjustedEvent(BookId bookId, int delta, int newQuantity, String actor) {

    public boolean isOutOfStock() {
        return newQuantity == 0;
    }
}
