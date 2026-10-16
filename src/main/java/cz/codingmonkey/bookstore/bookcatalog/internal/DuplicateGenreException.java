package cz.codingmonkey.bookstore.bookcatalog.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateGenreException extends RuntimeException {

    public DuplicateGenreException(String name) {
        super("Genre \"" + name + "\" already exists");
    }
}
