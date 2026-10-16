package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class PriceNotFoundException extends RuntimeException {

    public PriceNotFoundException(BookId bookId) {
        super("Book " + bookId + " has no price (not for sale)");
    }
}
