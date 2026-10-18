package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookCondition;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.pricing.PriceChangedEvent;
import cz.codingmonkey.bookstore.pricing.Prices;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import cz.codingmonkey.bookstore.shared.CurrentUser;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@EnableConfigurationProperties(PricingProperties.class)
public class PricingService implements Prices {

    /**
     * Current price by book id, including "no price" ({@code null}). Every price change evicts its book's entry when
     * the change commits, so the next read sees it.
     */
    static final String PRICE_CACHE = "prices";

    private static final ChangeLog changes = ChangeLog.of(PricingService.class);

    private final BookPriceRepository prices;
    private final PricePeriodRepository history;
    private final BookCatalog books;
    private final ApplicationEventPublisher events;
    private final PriceRules rules;

    PricingService(BookPriceRepository prices, PricePeriodRepository history, BookCatalog books,
            ApplicationEventPublisher events, PricingProperties properties) {
        this.prices = prices;
        this.history = history;
        this.books = books;
        this.events = events;
        this.rules = new PriceRules(properties.currency());
    }

    public PriceResponse findByBookId(BookId bookId) {
        return prices.findById(bookId).map(PriceResponse::from).orElseThrow(() -> new PriceNotFoundException(bookId));
    }

    @Override
    @Cacheable(cacheNames = PRICE_CACHE, key = "#bookId")
    public Optional<Money> priceOf(BookId bookId) {
        return prices.findById(bookId).map(BookPrice::getPrice);
    }

    @Override
    public BookCondition pricedBetween(@Nullable BigDecimal min, @Nullable BigDecimal max) {
        // EXISTS (select 1 from BookPrice p where p.bookId = <book> and p.price.amount between min and max)
        return (bookId, query, cb) -> {
            Subquery<Integer> inRange = query.subquery(Integer.class);
            Root<BookPrice> price = inRange.from(BookPrice.class);
            Path<BigDecimal> amount = price.get("price").get("amount");
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(price.get("bookId"), bookId));
            if (min != null) {
                where.add(cb.greaterThanOrEqualTo(amount, min));
            }
            if (max != null) {
                where.add(cb.lessThanOrEqualTo(amount, max));
            }
            inRange.select(cb.literal(1)).where(where.toArray(Predicate[]::new));
            return cb.exists(inRange);
        };
    }

    @Override
    public Map<BookId, Money> pricesOf(Collection<BookId> bookIds) {
        return findByBookIds(bookIds);
    }

    /** Prices of the given books, by book id; books without a price are left out. */
    public Map<BookId, Money> findByBookIds(Collection<BookId> bookIds) {
        return prices.findAllById(bookIds).stream()
                .collect(Collectors.toMap(BookPrice::getBookId, BookPrice::getPrice));
    }

    /**
     * @param bookIds optional: only prices of these books
     */
    public Page<PriceResponse> findAll(@Nullable Collection<BookId> bookIds, Pageable page) {
        Page<BookPrice> result = bookIds == null || bookIds.isEmpty()
                ? prices.findAllBy(page)
                : prices.findByBookIdIn(bookIds, page);
        return result.map(PriceResponse::from);
    }

    /**
     * A book's price history, newest first: the periods during which it had each price. Gaps between periods are
     * times without a price (not for sale). Empty for books that never had a price, including unknown ones.
     */
    public Page<PricePeriodResponse> findHistory(BookId bookId, Pageable page) {
        Sort newestFirst = Sort.by(Sort.Order.desc("validFrom"), Sort.Order.desc("id"));
        Pageable sorted = page.isPaged()
                ? PageRequest.of(page.getPageNumber(), page.getPageSize(), newestFirst)
                : Pageable.unpaged(newestFirst);
        return history.findByBookId(bookId, sorted).map(PricePeriodResponse::from);
    }

    /**
     * Sets or changes a book's price, which puts it on sale. The book stays locked against deletion until the
     * transaction commits, so a concurrent delete can't leave a price for a book that no longer exists.
     *
     * @param currencyCode optional; must be the store currency (see {@link PriceRules})
     */
    @Transactional
    @CacheEvict(cacheNames = PRICE_CACHE, key = "#bookId")
    public PriceResponse setPrice(BookId bookId, BigDecimal amount, @Nullable String currencyCode) {
        Money price = rules.toPrice(amount, currencyCode);

        if (!books.existsAndLockAgainstDeletion(bookId)) {
            throw new UnknownBookException(bookId);
        }

        BookPrice bookPrice = prices.findById(bookId).orElse(null);

        Money oldPrice = bookPrice == null ? null : bookPrice.getPrice();
        if (bookPrice == null) {
            bookPrice = prices.save(new BookPrice(bookId, price));
        } else {
            bookPrice.changeTo(price);
        }

        if (!price.equals(oldPrice)) {
            handlePriceChange(bookId, price, oldPrice);
        }

        return PriceResponse.from(bookPrice);
    }

    private void handlePriceChange(BookId bookId, Money price, @Nullable Money oldPrice) {
        Instant now = Instant.now();
        closeCurrentPeriod(bookId, now);
        history.save(new PricePeriod(bookId, price, now));
        events.publishEvent(new PriceChangedEvent(bookId, oldPrice, price, CurrentUser.id()));
        if (oldPrice == null) {
            changes.info("Set price of book {} to {}", bookId, price);
        } else {
            changes.info("Changed price of book {} from {} to {}", bookId, oldPrice, price);
        }
    }

    /** Removes a book's price, which takes it off sale. */
    @Transactional
    @CacheEvict(cacheNames = PRICE_CACHE, key = "#bookId")
    public void removePrice(BookId bookId) {
        BookPrice bookPrice = prices.findById(bookId).orElseThrow(() -> new PriceNotFoundException(bookId));
        prices.delete(bookPrice);
        closeCurrentPeriod(bookId, Instant.now());
        events.publishEvent(new PriceChangedEvent(bookId, bookPrice.getPrice(), null, CurrentUser.id()));
        changes.info("Removed price of book {} (was {})", bookId, bookPrice.getPrice());
    }

    /** A deleted book is no longer for sale. Its price history is kept. */
    @ApplicationModuleListener
    @CacheEvict(cacheNames = PRICE_CACHE, key = "#event.bookId()")
    public void on(BookDeletedEvent event) {
        prices.findById(event.bookId()).ifPresent(price -> {
            prices.delete(price);
            closeCurrentPeriod(event.bookId(), Instant.now());
            events.publishEvent(new PriceChangedEvent(event.bookId(), price.getPrice(), null, event.actor()));
            changes.info("Removed price of deleted book {} (was {})", event.bookId(), price.getPrice());
        });
    }

    private void closeCurrentPeriod(BookId bookId, Instant at) {
        // Flush so the closed period is written before a new open one; at most one may be open per book.
        history.findByBookIdAndValidUntilIsNull(bookId).ifPresent(period -> {
            period.close(at);
            history.flush();
        });
    }
}
