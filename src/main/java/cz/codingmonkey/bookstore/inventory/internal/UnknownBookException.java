package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UnknownBookException extends RuntimeException {

    public UnknownBookException(BookId bookId) {
        super("Book " + bookId + " is not in the catalog");
    }
}
