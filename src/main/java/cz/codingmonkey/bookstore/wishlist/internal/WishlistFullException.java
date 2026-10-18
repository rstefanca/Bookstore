package cz.codingmonkey.bookstore.wishlist.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class WishlistFullException extends RuntimeException {

    WishlistFullException() {
        super("Your wishlist is full: at most " + WishlistService.LIMIT + " books");
    }
}
