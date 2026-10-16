package cz.codingmonkey.bookstore.bookcatalog.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class BookDeletionVetoedException extends RuntimeException {

    public BookDeletionVetoedException(String reason) {
        super(reason);
    }
}
