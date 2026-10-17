package cz.codingmonkey.bookstore.storefront;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidOrderingException extends RuntimeException {

    InvalidOrderingException(String message) {
        super(message);
    }
}
