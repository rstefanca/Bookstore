package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(BookId bookId, int requested, int available) {
        super("Cannot withdraw %d of book %s, only %d in stock".formatted(requested, bookId, available));
    }
}
