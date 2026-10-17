/**
 * Pricing module. Holds the list price of each book; a book without a price is in the catalog but not for sale.
 * Prices are in the store currency. Exposes {@link cz.codingmonkey.bookstore.pricing.Money} and publishes
 * {@link cz.codingmonkey.bookstore.pricing.PriceChangedEvent}.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Pricing")
package cz.codingmonkey.bookstore.pricing;
