package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class GenreInUseException extends RuntimeException {

    public GenreInUseException(GenreId id) {
        super("Genre " + id + " is still assigned to books");
    }
}
