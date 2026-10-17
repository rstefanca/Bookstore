package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.bookcatalog.BookDetails.AuthorSummary;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails.GenreSummary;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.pricing.Money;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A book as customers see it: the catalog's details plus the current price.
 *
 * @param authors in credited order
 * @param genres  sorted by name
 * @param price   {@code null} if the book has no price, i.e. is not for sale
 */
record StorefrontBook(
        BookId id,
        String title,
        List<AuthorSummary> authors,
        List<GenreSummary> genres,
        String isbn,
        @Nullable Integer publishedYear,
        @Nullable String description,
        @Nullable Money price
) {

    static StorefrontBook of(BookDetails book, @Nullable Money price) {
        return new StorefrontBook(book.id(), book.title(), book.authors(), book.genres(), book.isbn(),
                book.publishedYear(), book.description(), price);
    }
}
