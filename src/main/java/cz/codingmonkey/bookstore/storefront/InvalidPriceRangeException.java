package cz.codingmonkey.bookstore.storefront;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidPriceRangeException extends RuntimeException {

    InvalidPriceRangeException(String message) {
        super(message);
    }
}
