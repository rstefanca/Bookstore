package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class AuthorHasBooksException extends RuntimeException {

    public AuthorHasBooksException(AuthorId id) {
        super("Author " + id + " still has books in the catalog");
    }
}
