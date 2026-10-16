package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.ordering.OrderId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class OrderNotFoundException extends RuntimeException {

    OrderNotFoundException(OrderId id) {
        super("Order " + id + " not found");
    }
}
