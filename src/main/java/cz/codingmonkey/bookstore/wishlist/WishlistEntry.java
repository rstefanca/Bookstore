package cz.codingmonkey.bookstore.wishlist;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.time.Instant;

/** A book on a customer's wishlist and when it was added. */
public record WishlistEntry(BookId bookId, Instant addedAt) {
}
