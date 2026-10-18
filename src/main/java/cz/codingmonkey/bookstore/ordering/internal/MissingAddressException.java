package cz.codingmonkey.bookstore.ordering.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
class MissingAddressException extends RuntimeException {

    MissingAddressException(boolean shipping, boolean invoice) {
        super(shipping && invoice
                ? "Shipping and invoice addresses are required: send them or save them"
                : (shipping ? "Shipping" : "Invoice") + " address is required: send one or save one");
    }
}
