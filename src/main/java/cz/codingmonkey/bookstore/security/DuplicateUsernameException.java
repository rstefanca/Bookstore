package cz.codingmonkey.bookstore.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class DuplicateUsernameException extends RuntimeException {

    DuplicateUsernameException(String username) {
        super("Username \"" + username + "\" is taken");
    }
}
