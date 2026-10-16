package cz.codingmonkey.bookstore.ordering.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class EmptyCartException extends RuntimeException {

    EmptyCartException() {
        super("The cart is empty");
    }
}
