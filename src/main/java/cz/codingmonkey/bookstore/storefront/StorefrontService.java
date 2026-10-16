package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.pricing.Prices;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Combines catalog details and prices. Not transactional itself: each module reads in its own transaction.
 */
@Service
class StorefrontService {

    private final BookCatalog catalog;
    private final Prices prices;

    /** Runs independent queries side by side, each on its own virtual thread. */
    private final TaskExecutor queries = new VirtualThreadTaskExecutor("storefront-query-");

    StorefrontService(BookCatalog catalog, Prices prices) {
        this.catalog = catalog;
        this.prices = prices;
    }

    /**
     * Fetches the book and its price in parallel: neither query needs the other's result. Each uses its own
     * database connection while they overlap.
     */
    StorefrontBook findBook(BookId id) {
        CompletableFuture<Optional<BookDetails>> book =
                CompletableFuture.supplyAsync(() -> catalog.findBook(id), queries);
        CompletableFuture<Optional<Money>> price = CompletableFuture.supplyAsync(() -> prices.priceOf(id), queries);

        BookDetails details = join(book).orElseThrow(() -> new BookNotFoundException(id));
        return StorefrontBook.of(details, join(price).orElse(null));
    }

    /**
     * A page of books with their prices. The price lookup needs the page's book ids, so the two queries run one
     * after the other: one for the page, one for all its prices.
     * <p>
     * A price range becomes a condition from pricing that the catalog applies in its own query (an {@code EXISTS}
     * subquery on prices), so paging and totals stay correct and matching ids never leave the database. Books without
     * a price never match a price range.
     */
    Page<StorefrontBook> findBooks(BookSearch search, PriceRange priceRange, Pageable page) {
        if (!priceRange.isAny()) {
            search = search.matching(prices.pricedBetween(priceRange.min(), priceRange.max()));
        }
        Page<BookDetails> books = catalog.findBooks(search, page);
        Map<BookId, Money> bookPrices = prices.pricesOf(books.map(BookDetails::id).getContent());
        return books.map(book -> StorefrontBook.of(book, bookPrices.get(book.id())));
    }

    /** Waits for the result, rethrowing the query's own exception rather than a wrapper. */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }
}
