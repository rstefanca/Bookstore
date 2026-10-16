package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class InventoryItemNotFoundException extends RuntimeException {

    public InventoryItemNotFoundException(BookId bookId) {
        super("No inventory for book " + bookId);
    }
}
