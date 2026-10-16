package cz.codingmonkey.bookstore.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class LastAdminException extends RuntimeException {

    LastAdminException(String message) {
        super(message);
    }
}
