package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class UnknownBookException extends RuntimeException {

    UnknownBookException(BookId bookId) {
        super("No book with id " + bookId.value());
    }
}
