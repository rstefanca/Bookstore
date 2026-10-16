package cz.codingmonkey.bookstore.bookcatalog.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A book request referenced unknown or duplicate authors. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidAuthorsException extends RuntimeException {

    public InvalidAuthorsException(String message) {
        super(message);
    }
}
