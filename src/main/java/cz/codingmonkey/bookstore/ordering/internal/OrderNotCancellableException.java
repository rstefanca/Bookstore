package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.ordering.OrderId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class OrderNotCancellableException extends RuntimeException {

    OrderNotCancellableException(OrderId id, OrderStatus status) {
        super("Order %s is %s; only confirmed orders can be cancelled".formatted(id, status));
    }
}
