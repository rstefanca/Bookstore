package cz.codingmonkey.bookstore.inventory;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.shared.Auditable;
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
public record StockAdjustedEvent(BookId bookId, int delta, int newQuantity, String actor) implements Auditable {

    public boolean isOutOfStock() {
        return newQuantity == 0;
    }

    @Override
    public String auditAction() {
        return "STOCK_ADJUSTED";
    }

    @Override
    public BookId auditSubject() {
        return bookId;
    }

    @Override
    public String auditDescription() {
        String change = delta > 0 ? "Restocked %d".formatted(delta) : "Withdrew %d".formatted(-delta);
        return "%s, now %d in stock%s".formatted(change, newQuantity, isOutOfStock() ? " (OUT OF STOCK)" : "");
    }
}
