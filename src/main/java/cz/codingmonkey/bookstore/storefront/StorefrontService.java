package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.pricing.Prices;
import cz.codingmonkey.bookstore.rating.RatingSummary;
import cz.codingmonkey.bookstore.rating.Ratings;
import java.util.List;
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
 * Combines catalog details, prices and rating summaries. Not transactional itself: each module reads in its own
 * transaction.
 */
@Service
class StorefrontService {

    private final BookCatalog catalog;
    private final Prices prices;
    private final Ratings ratings;

    /** Runs independent queries side by side, each on its own virtual thread. */
    private final TaskExecutor queries = new VirtualThreadTaskExecutor("storefront-query-");

    StorefrontService(BookCatalog catalog, Prices prices, Ratings ratings) {
        this.catalog = catalog;
        this.prices = prices;
        this.ratings = ratings;
    }

    /**
     * Fetches the book, its price and its rating summary in parallel: no query needs another's result. Each uses
     * its own database connection while they overlap.
     */
    StorefrontBook findBook(BookId id) {
        CompletableFuture<Optional<BookDetails>> book =
                CompletableFuture.supplyAsync(() -> catalog.findBook(id), queries);
        CompletableFuture<Optional<Money>> price = CompletableFuture.supplyAsync(() -> prices.priceOf(id), queries);
        CompletableFuture<RatingSummary> rating =
                CompletableFuture.supplyAsync(() -> ratings.summaryOf(id), queries);

        BookDetails details = join(book).orElseThrow(() -> new BookNotFoundException(id));
        return StorefrontBook.of(details, join(price).orElse(null), join(rating));
    }

    /**
     * A page of books with their prices and rating summaries. Both lookups need the page's book ids, so they run
     * after the page query, side by side: one for all the prices, one for all the rating summaries.
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
        List<BookId> ids = books.map(BookDetails::id).getContent();
        CompletableFuture<Map<BookId, Money>> bookPrices =
                CompletableFuture.supplyAsync(() -> prices.pricesOf(ids), queries);
        CompletableFuture<Map<BookId, RatingSummary>> bookRatings =
                CompletableFuture.supplyAsync(() -> ratings.summariesOf(ids), queries);
        Map<BookId, Money> pricesById = join(bookPrices);
        Map<BookId, RatingSummary> ratingsById = join(bookRatings);
        return books.map(book -> StorefrontBook.of(book, pricesById.get(book.id()),
                ratingsById.getOrDefault(book.id(), RatingSummary.NONE)));
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
