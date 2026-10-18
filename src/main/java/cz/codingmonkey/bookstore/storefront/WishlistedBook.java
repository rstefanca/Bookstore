package cz.codingmonkey.bookstore.storefront;

import java.time.Instant;

/**
 * A book on a customer's wishlist, as customers see books, with the time it was added.
 */
record WishlistedBook(StorefrontBook book, Instant addedAt) {
}
