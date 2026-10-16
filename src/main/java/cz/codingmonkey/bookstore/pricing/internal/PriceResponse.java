package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.pricing.Money;

/**
 * @param price {@code null} if the book has no price, i.e. is not for sale
 */
public record PriceResponse(BookId bookId, Money price) {

    static PriceResponse from(BookPrice price) {
        return new PriceResponse(price.getBookId(), price.getPrice());
    }
}
