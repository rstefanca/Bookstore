package cz.codingmonkey.bookstore.ordering.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidCartException extends RuntimeException {

    InvalidCartException(String message) {
        super(message);
    }
}
