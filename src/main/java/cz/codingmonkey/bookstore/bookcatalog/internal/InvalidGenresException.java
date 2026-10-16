package cz.codingmonkey.bookstore.bookcatalog.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A book request referenced unknown genres. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidGenresException extends RuntimeException {

    public InvalidGenresException(String message) {
        super(message);
    }
}
