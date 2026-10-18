/**
 * Storefront module: the customer-facing view of books, composed from other modules' public APIs (the catalog's
 * {@link cz.codingmonkey.bookstore.bookcatalog.BookCatalog}, pricing's
 * {@link cz.codingmonkey.bookstore.pricing.Prices}, rating's {@link cz.codingmonkey.bookstore.rating.Ratings} and
 * recommendation's {@link cz.codingmonkey.bookstore.recommendation.Recommendations} and wishlist's
 * {@link cz.codingmonkey.bookstore.wishlist.Wishlist}), so those modules don't need to know about each other.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Storefront")
package cz.codingmonkey.bookstore.storefront;
