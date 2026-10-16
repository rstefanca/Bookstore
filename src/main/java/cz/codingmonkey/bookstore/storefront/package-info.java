/**
 * Storefront module: the customer-facing view of books, composed from other modules' public APIs (the catalog's
 * {@link cz.codingmonkey.bookstore.bookcatalog.BookCatalog} and pricing's
 * {@link cz.codingmonkey.bookstore.pricing.Prices}), so those modules don't need to know about each other.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Storefront")
package cz.codingmonkey.bookstore.storefront;
