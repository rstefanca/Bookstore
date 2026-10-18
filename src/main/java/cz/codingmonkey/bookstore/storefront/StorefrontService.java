package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookCondition;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.pricing.Prices;
import cz.codingmonkey.bookstore.rating.RatingSummary;
import cz.codingmonkey.bookstore.rating.Ratings;
import cz.codingmonkey.bookstore.recommendation.Recommendation;
import cz.codingmonkey.bookstore.recommendation.Recommendations;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.wishlist.Wishlist;
import cz.codingmonkey.bookstore.wishlist.WishlistEntry;
import org.jspecify.annotations.Nullable;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;

/**
 * Combines catalog details, prices, rating summaries and recommendations. Not transactional itself: each module reads
 * in its own transaction.
 */
@Service
class StorefrontService {

    /**
     * How many recommendations to consider per requested similar book. Books that aren't for sale are left out, so
     * more candidates are fetched than will be shown.
     */
    static final int CANDIDATES_PER_RESULT = 4;

    private final BookCatalog catalog;
    private final Prices prices;
    private final Ratings ratings;
    private final Recommendations recommendations;
    private final Wishlist wishlist;

    /**
     * Runs independent queries side by side, each on its own virtual thread.
     */
    private final TaskExecutor queries = new VirtualThreadTaskExecutor("storefront-query-");

    StorefrontService(BookCatalog catalog, Prices prices, Ratings ratings, Recommendations recommendations,
                      Wishlist wishlist) {
        this.catalog = catalog;
        this.prices = prices;
        this.ratings = ratings;
        this.recommendations = recommendations;
        this.wishlist = wishlist;
    }

    /**
     * Fetches the book, its price and its rating summary in parallel: no query needs another's result. Each uses
     * its own database connection while they overlap.
     */
    StorefrontBook findBook(BookId id) {
        CompletableFuture<Optional<BookDetails>> book = getBookDetailsAsync(id);
        CompletableFuture<Optional<Money>> price = getPriceAsync(id);
        CompletableFuture<RatingSummary> rating = getRatingAsync(id);

        BookDetails details = join(book).orElseThrow(() -> new BookNotFoundException(id));
        return StorefrontBook.of(details, join(price).orElse(null), join(rating));
    }

    private CompletableFuture<RatingSummary> getRatingAsync(BookId id) {
        return CompletableFuture.supplyAsync(() -> ratings.summaryOf(id), queries);
    }

    private CompletableFuture<Optional<Money>> getPriceAsync(BookId id) {
        return CompletableFuture.supplyAsync(() -> prices.priceOf(id), queries);
    }

    private CompletableFuture<Optional<BookDetails>> getBookDetailsAsync(BookId id) {
        return CompletableFuture.supplyAsync(() -> catalog.findBook(id), queries);
    }

    /**
     * A page of books with their prices and rating summaries. Both lookups need the page's book ids, so they run
     * after the page query, side by side: one for all the prices, one for all the rating summaries.
     * <p>
     * A price range becomes a condition from pricing that the catalog applies in its own query (an {@code EXISTS}
     * subquery on prices), so paging and totals stay correct and matching ids never leave the database. Books without
     * a price never match a price range.
     * <p>
     * A minimum rating becomes a condition from the rating module and the rating order an order from it, applied the
     * same way; both combine with the price range and the other filters. The caller prepares the page for the
     * ordering ({@link BookOrdering#pageOf}).
     */
    Page<StorefrontBook> findBooks(BookSearch search,
                                   PriceRange priceRange,
                                   @Nullable BigDecimal minRating,
                                   BookOrdering ordering,
                                   Pageable page) {
        if (!priceRange.isAny()) {
            search = search.matching(prices.pricedBetween(priceRange.min(), priceRange.max()));
        }
        if (minRating != null) {
            search = search.matching(ratings.ratedAtLeast(minRating));
        }
        if (ordering == BookOrdering.RATING) {
            search = search.ordered(ratings.byRating());
        }
        Page<BookDetails> books = catalog.findBooks(search, page);
        List<BookId> ids = books.map(BookDetails::id).getContent();

        CompletableFuture<Map<BookId, Money>> bookPrices = getBookPricesAsync(ids);
        CompletableFuture<Map<BookId, RatingSummary>> bookRatings = getBookRatingsAsync(ids);

        Map<BookId, Money> pricesById = join(bookPrices);
        Map<BookId, RatingSummary> ratingsById = join(bookRatings);
        return books.map(book -> StorefrontBook.of(book, pricesById.get(book.id()),
                ratingsById.getOrDefault(book.id(), RatingSummary.NONE)));
    }

    /**
     * Up to {@code limit} books similar to the given one that are for sale, most similar first, with their details,
     * prices and rating summaries. Fewer if not enough of the closest {@link #CANDIDATES_PER_RESULT} × limit
     * recommendations are for sale.
     * <p>
     * The prices are needed first, to pick the books; their details and ratings are then fetched side by side.
     * Recommendations can briefly include a book that was just deleted; the catalog no longer has it, so it is
     * skipped.
     *
     * @throws NoRecommendationsException if the book is unknown or hasn't been indexed yet
     */
    List<SimilarBook> findSimilar(BookId id, int limit) {
        List<Recommendation> candidates = recommendations.similarTo(id, limit * CANDIDATES_PER_RESULT)
                .orElseThrow(() -> new NoRecommendationsException(id));
        Map<BookId, Money> pricesById = prices.pricesOf(candidates.stream().map(Recommendation::bookId).toList());
        List<Recommendation> forSale = candidates.stream()
                .filter(candidate -> pricesById.containsKey(candidate.bookId()))
                .limit(limit)
                .toList();
        if (forSale.isEmpty()) {
            return List.of();
        }

        List<BookId> bookIds = forSale.stream().map(Recommendation::bookId).toList();
        CompletableFuture<Map<BookId, BookDetails>> bookDetails = retrieveBooksByIdsAsync(bookIds);
        CompletableFuture<Map<BookId, RatingSummary>> bookRatings = getBookRatingsAsync(bookIds);
        Map<BookId, BookDetails> detailsById = join(bookDetails);
        Map<BookId, RatingSummary> ratingsById = join(bookRatings);
        return forSale.stream()
                .filter(recommendation -> detailsById.containsKey(recommendation.bookId()))
                .map(recommendation -> {
                    var book = detailsById.get(recommendation.bookId());
                    var price = pricesById.get(recommendation.bookId());
                    var rating = ratingsById.getOrDefault(recommendation.bookId(), RatingSummary.NONE);
                    return SimilarBook.of(book, price, rating, recommendation.similarity());
                })
                .toList();
    }

    /**
     * A page of the customer's wishlist with the books' details, prices and rating summaries, newest added first.
     * The details, prices and ratings are fetched side by side once the page of entries is known. A book that was
     * just deleted is skipped (the wishlist drops its entries a moment later), so the page may have fewer items
     * than its size.
     */
    Page<WishlistedBook> findWishlist(AccountId account, Pageable page) {
        Page<WishlistEntry> entries = wishlist.entriesOf(account, page);
        if (entries.isEmpty()) {
            return new PageImpl<>(List.of(), page, entries.getTotalElements());
        }

        List<BookId> bookIds = entries.map(WishlistEntry::bookId).getContent();
        CompletableFuture<Map<BookId, BookDetails>> bookDetails = retrieveBooksByIdsAsync(bookIds);
        CompletableFuture<Map<BookId, Money>> bookPrices = getBookPricesAsync(bookIds);
        CompletableFuture<Map<BookId, RatingSummary>> bookRatings = getBookRatingsAsync(bookIds);
        Map<BookId, BookDetails> detailsById = join(bookDetails);
        Map<BookId, Money> pricesById = join(bookPrices);
        Map<BookId, RatingSummary> ratingsById = join(bookRatings);

        List<WishlistedBook> items = entries.stream()
                .filter(entry -> detailsById.containsKey(entry.bookId()))
                .map(entry -> {
                    var book = detailsById.get(entry.bookId());
                    var price = pricesById.get(entry.bookId());
                    var rating = ratingsById.getOrDefault(entry.bookId(), RatingSummary.NONE);
                    return new WishlistedBook(StorefrontBook.of(book, price, rating), entry.addedAt());
                })
                .toList();
        return new PageImpl<>(items, page, entries.getTotalElements());
    }

    private CompletableFuture<Map<BookId, RatingSummary>> getBookRatingsAsync(List<BookId> ids) {
        return CompletableFuture.supplyAsync(() -> ratings.summariesOf(ids), queries);
    }

    private CompletableFuture<Map<BookId, BookDetails>> retrieveBooksByIdsAsync(List<BookId> ids) {
        BookSearch mathcingBookSearch = BookSearch.EMPTY.matching(among(ids));
        return CompletableFuture.supplyAsync(() -> findBooks(mathcingBookSearch), queries);
    }

    private Map<BookId, BookDetails> findBooks(BookSearch mathcingBookSearch) {
        return catalog.findBooks(mathcingBookSearch, Pageable.unpaged()).stream()
                .collect(toMap(BookDetails::id, Function.identity()));
    }

    private CompletableFuture<Map<BookId, Money>> getBookPricesAsync(List<BookId> ids) {
        return CompletableFuture.supplyAsync(() -> prices.pricesOf(ids), queries);
    }

    /**
     * Limits a catalog search to the given books.
     */
    private static BookCondition among(Collection<BookId> ids) {
        return (bookId, query, cb) -> bookId.in(ids);
    }

    /**
     * Waits for the result, rethrowing the query's own exception rather than a wrapper.
     */
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
