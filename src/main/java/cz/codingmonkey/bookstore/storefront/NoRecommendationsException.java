package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class NoRecommendationsException extends RuntimeException {

    NoRecommendationsException(BookId id) {
        super("No recommendations for book " + id
                + " yet (unknown book, or it was added moments ago and isn't indexed yet)");
    }
}
