package cz.codingmonkey.bookstore.rating.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class RatingNotFoundException extends RuntimeException {

    RatingNotFoundException() {
        super("You haven't rated this book");
    }
}
