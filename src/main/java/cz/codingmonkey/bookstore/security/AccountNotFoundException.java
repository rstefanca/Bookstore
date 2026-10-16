package cz.codingmonkey.bookstore.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class AccountNotFoundException extends RuntimeException {

    AccountNotFoundException(AccountId id) {
        super("Account " + id + " not found");
    }
}
