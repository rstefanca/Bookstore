package cz.codingmonkey.bookstore.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorService;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookService;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.pricing.Prices;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link Prices#priceOf} is cached, and every price change evicts it when the change commits. To tell cached from
 * fresh reads, tests change prices directly in the database, bypassing the service and its evictions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class PricingCachingIntegrationTest {

    static final Money TEN = Money.of("10", "EUR");

    @Autowired
    Prices prices;

    @Autowired
    PricingService pricing;

    @Autowired
    BookService books;

    @Autowired
    AuthorService authors;

    @Autowired
    CacheManager caches;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    RestTestClient client;

    BookId book;

    @BeforeEach
    void setUp() {
        caches.getCacheNames().forEach(name -> caches.getCache(name).clear());
        var author = authors.create(new AuthorRequest("Priced Author", Set.of())).id();
        String isbn = "price-cache-" + UUID.randomUUID().toString().substring(0, 8);
        book = books.create(new BookRequest("Cached Book", List.of(author), Set.of(), isbn, 2000, null)).id();
        pricing.setPrice(book, new BigDecimal("10"), null);
    }

    @Test
    void cachesPrices() {
        assertThat(prices.priceOf(book)).contains(TEN);

        changePriceInDatabase("99");

        assertThat(prices.priceOf(book)).as("served from the cache").contains(TEN);
    }

    @Test
    void cachesThatABookIsNotForSale() {
        pricing.removePrice(book);
        assertThat(prices.priceOf(book)).isEmpty();

        jdbc.update("INSERT INTO book_price (book_id, amount, currency, version) VALUES (?, 99, 'EUR', 0)",
                book.value());

        assertThat(prices.priceOf(book)).as("served from the cache").isEmpty();
    }

    @Test
    void settingAPriceEvictsImmediately() {
        prices.priceOf(book);

        pricing.setPrice(book, new BigDecimal("8.50"), null);

        assertThat(prices.priceOf(book)).contains(Money.of("8.50", "EUR"));
    }

    @Test
    void pricingABookThatWasNotForSaleEvictsImmediately() {
        pricing.removePrice(book);
        assertThat(prices.priceOf(book)).isEmpty();

        pricing.setPrice(book, new BigDecimal("12"), null);

        assertThat(prices.priceOf(book)).contains(Money.of("12", "EUR"));
    }

    @Test
    void removingAPriceEvictsImmediately() {
        prices.priceOf(book);

        pricing.removePrice(book);

        assertThat(prices.priceOf(book)).isEmpty();
    }

    @Test
    void deletingTheBookEvicts() {
        prices.priceOf(book);

        books.delete(book);

        // The price is removed by an asynchronous listener after the book's deletion commits.
        await().untilAsserted(() -> assertThat(prices.priceOf(book)).isEmpty());
    }

    @Test
    void rolledBackChangesDoNotEvict() {
        prices.priceOf(book);
        changePriceInDatabase("99");

        transaction.executeWithoutResult(status -> {
            pricing.setPrice(book, new BigDecimal("5"), null);
            status.setRollbackOnly();
        });

        assertThat(prices.priceOf(book)).as("still cached").contains(TEN);
    }

    @Test
    void storefrontShowsPriceChangesImmediately() {
        client.get().uri("/api/books/{id}", book).exchange().expectBody().jsonPath("$.price.amount").isEqualTo(10.00);

        client.put().uri("/api/books/{id}/price", book)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": 7.25}")
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/api/books/{id}", book).exchange().expectBody().jsonPath("$.price.amount").isEqualTo(7.25);
    }

    private void changePriceInDatabase(String amount) {
        jdbc.update("UPDATE book_price SET amount = ?::numeric WHERE book_id = ?", amount, book.value());
    }
}
