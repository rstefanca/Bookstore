package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookDeletionPolicy;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Prevents deleting a book while copies of it are in stock.
 */
@Component
class InStockBookDeletionPolicy implements BookDeletionPolicy {

    private final InventoryService inventory;

    InStockBookDeletionPolicy(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Override
    public Optional<String> vetoReason(BookId bookId) {
        return inventory.isInStock(bookId)
                ? Optional.of("Book %s is still in stock; withdraw all copies before deleting it".formatted(bookId))
                : Optional.empty();
    }
}
