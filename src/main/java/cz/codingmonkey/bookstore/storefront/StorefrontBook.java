package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails.AuthorSummary;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails.GenreSummary;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.pricing.Money;
import java.util.List;

/**
 * A book as customers see it: the catalog's details plus the current price.
 *
 * @param price {@code null} if the book has no price, i.e. is not for sale
 */
record StorefrontBook(
        BookId id,
        String title,
        List<AuthorSummary> authors,
        List<GenreSummary> genres,
        String isbn,
        Integer publishedYear,
        String description,
        Money price
) {

    static StorefrontBook of(BookDetails book, Money price) {
        return new StorefrontBook(book.id(), book.title(), book.authors(), book.genres(), book.isbn(),
                book.publishedYear(), book.description(), price);
    }
}
