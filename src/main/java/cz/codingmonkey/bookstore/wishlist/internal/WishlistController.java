package cz.codingmonkey.bookstore.wishlist.internal;

import static org.springframework.http.HttpStatus.NO_CONTENT;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.shared.CurrentUser;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adds books to and removes them from the signed-in customer's wishlist; the list itself is read under
 * {@code GET /api/me/wishlist}.
 */
@RestController
@RequestMapping("/api/me/wishlist")
@Tag(name = "Wishlist", description = """
        Customers (`CUSTOMER`) keep a list of books they want to remember, at most 200. Books without a price stay \
        on the list with a `null` price.""")
class WishlistController {

    private final WishlistService wishlist;

    WishlistController(WishlistService wishlist) {
        this.wishlist = wishlist;
    }

    /**
     * Puts a book on your wishlist; nothing changes if it is already there.
     *
     * @param bookId the book
     */
    @PutMapping("/{bookId}")
    @ResponseStatus(NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "The book is on your wishlist")
    @ApiResponse(responseCode = "404", description = "There is no book with this id")
    @ApiResponse(responseCode = "409", description = "Your wishlist is full: at most 200 books")
    void add(@PathVariable BookId bookId) {
        wishlist.add(CurrentUser.requireId(), bookId);
    }

    /**
     * Takes a book off your wishlist; also succeeds when it isn't on it.
     *
     * @param bookId the book
     */
    @DeleteMapping("/{bookId}")
    @ResponseStatus(NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "The book is not on your wishlist")
    void remove(@PathVariable BookId bookId) {
        wishlist.remove(CurrentUser.requireId(), bookId);
    }
}
