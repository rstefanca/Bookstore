package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;

/**
 * A book's stock.
 *
 * @param quantity copies in stock
 */
public record InventoryResponse(BookId bookId, int quantity) {

    static InventoryResponse from(InventoryItem item) {
        return new InventoryResponse(item.getBookId(), item.getQuantity());
    }
}
