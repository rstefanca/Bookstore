package cz.codingmonkey.bookstore.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.pricing.internal.InvalidPriceException;
import cz.codingmonkey.bookstore.pricing.internal.PriceNotFoundException;
import cz.codingmonkey.bookstore.pricing.internal.PricePeriodResponse;
import cz.codingmonkey.bookstore.pricing.internal.PricingService;
import cz.codingmonkey.bookstore.pricing.internal.UnknownBookException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Bootstraps only the pricing module; the catalog's {@link BookCatalog} is mocked.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class PricingModuleTests {

    @MockitoBean
    BookCatalog books;

    @Autowired
    PricingService pricing;

    @Autowired
    EventSerializer serializer;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void priceEventsSurviveTheEventPublicationRegistry() {
        var event = new PriceChangedEvent(new BookId(7), "Dune", Money.of("12.90", "EUR"), null, null);

        Object serialized = serializer.serialize(event);

        assertThat(serializer.deserialize(serialized, PriceChangedEvent.class)).isEqualTo(event);
    }

    @BeforeEach
    void allBooksExist() {
        given(books.existsAndLockAgainstDeletion(any())).willReturn(true);
    }

    @Test
    void settingAPricePutsTheBookOnSale(Scenario scenario) {
        BookId bookId = new BookId(200);
        given(books.findBook(bookId)).willReturn(Optional.of(
                new BookDetails(bookId, "Dune", List.of(), List.of(), "isbn", null, null)));

        scenario.stimulate(() -> pricing.setPrice(bookId, new BigDecimal("12.9"), null))
                .andWaitForEventOfType(PriceChangedEvent.class)
                .matching(event -> event.bookId().equals(bookId))
                .toArriveAndVerify(event -> {
                    assertThat(event.title()).as("for the audit log").isEqualTo("Dune");
                    assertThat(event.oldPrice()).isNull();
                    assertThat(event.newPrice()).isEqualTo(Money.of("12.90", "EUR"));
                });

        assertThat(pricing.findByBookId(bookId).price()).as("store currency by default")
                .isEqualTo(Money.of("12.90", "EUR"));
    }

    @Test
    void changingThePricePublishesOldAndNew(Scenario scenario) {
        BookId bookId = new BookId(201);
        pricing.setPrice(bookId, new BigDecimal("10.00"), "EUR");

        scenario.stimulate(() -> pricing.setPrice(bookId, new BigDecimal("8.50"), "EUR"))
                .andWaitForEventOfType(PriceChangedEvent.class)
                .matching(event -> event.bookId().equals(bookId) && event.oldPrice() != null)
                .toArriveAndVerify(event -> {
                    assertThat(event.oldPrice()).isEqualTo(Money.of("10", "EUR"));
                    assertThat(event.newPrice()).isEqualTo(Money.of("8.5", "EUR"));
                });
    }

    @Test
    void settingTheSamePriceAgainPublishesNothing(PublishedEvents events) {
        BookId bookId = new BookId(202);
        pricing.setPrice(bookId, new BigDecimal("10"), "EUR");

        pricing.setPrice(bookId, new BigDecimal("10.00"), "EUR");

        assertThat(events.ofType(PriceChangedEvent.class).matching(e -> e.bookId().equals(bookId))).hasSize(1);
    }

    @Test
    void rejectedPricesAreNotStored() {
        // The rules themselves are covered by PriceRulesTest; this checks the service applies them before storing.
        BookId bookId = new BookId(203);

        assertThatThrownBy(() -> pricing.setPrice(bookId, new BigDecimal("10"), "USD"))
                .isInstanceOf(InvalidPriceException.class).hasMessage("Prices must be in EUR, got USD");
        assertThatThrownBy(() -> pricing.findByBookId(bookId)).isInstanceOf(PriceNotFoundException.class);
    }

    @Test
    void rejectsPricesForBooksNotInTheCatalog() {
        BookId bookId = new BookId(204);
        given(books.existsAndLockAgainstDeletion(bookId)).willReturn(false);

        assertThatThrownBy(() -> pricing.setPrice(bookId, BigDecimal.TEN, null))
                .isInstanceOf(UnknownBookException.class);
    }

    @Test
    void removingThePriceTakesTheBookOffSale(Scenario scenario) {
        BookId bookId = new BookId(205);
        pricing.setPrice(bookId, BigDecimal.TEN, null);

        scenario.stimulate(() -> pricing.removePrice(bookId))
                .andWaitForEventOfType(PriceChangedEvent.class)
                .matching(event -> event.bookId().equals(bookId) && event.newPrice() == null)
                .toArrive();

        assertThatThrownBy(() -> pricing.findByBookId(bookId)).isInstanceOf(PriceNotFoundException.class);
    }

    @Test
    void deletedBooksLoseTheirPrice(Scenario scenario) {
        BookId bookId = new BookId(206);
        pricing.setPrice(bookId, BigDecimal.TEN, null);

        scenario.publish(new BookDeletedEvent(bookId, "Dune", "isbn", "jane"))
                .andWaitForEventOfType(PriceChangedEvent.class)
                .matching(event -> event.bookId().equals(bookId) && event.newPrice() == null)
                .toArriveAndVerify(event -> {
                    assertThat(event.actor()).as("whoever deleted the book removed its price").isEqualTo("jane");
                    assertThat(event.title()).as("no longer in the catalog").isEqualTo("Dune");
                });

        assertThat(pricing.findByBookIds(List.of(bookId))).isEmpty();
    }

    @Test
    void recordsPriceHistoryNewestFirst() {
        BookId bookId = new BookId(207);
        pricing.setPrice(bookId, new BigDecimal("10"), null);
        pricing.setPrice(bookId, new BigDecimal("10.00"), null); // unchanged: no new period
        pricing.setPrice(bookId, new BigDecimal("8.50"), null);
        pricing.removePrice(bookId);
        pricing.setPrice(bookId, new BigDecimal("9"), null);

        List<PricePeriodResponse> history = pricing.findHistory(bookId, Pageable.unpaged()).getContent();

        assertThat(history).extracting(PricePeriodResponse::price)
                .containsExactly(Money.of("9", "EUR"), Money.of("8.50", "EUR"), Money.of("10", "EUR"));
        var current = history.get(0);
        var discounted = history.get(1);
        var original = history.get(2);
        assertThat(current.until()).as("current price").isNull();
        assertThat(original.until()).as("changed directly: no gap").isEqualTo(discounted.from());
        assertThat(discounted.until()).as("removed, then priced again: a gap")
                .isNotNull().isBeforeOrEqualTo(current.from());
        assertThat(pricing.findHistory(bookId, PageRequest.of(0, 1)).getContent())
                .extracting(PricePeriodResponse::price).containsExactly(Money.of("9", "EUR"));
    }

    @Test
    void keepsHistoryOfDeletedBooks(Scenario scenario) {
        BookId bookId = new BookId(208);
        pricing.setPrice(bookId, BigDecimal.TEN, null);

        scenario.publish(new BookDeletedEvent(bookId, "Dune", "isbn", null))
                .andWaitForStateChange(() -> pricing.findHistory(bookId, Pageable.unpaged()).getContent().getFirst(),
                        period -> period.until() != null)
                .andVerify(period -> assertThat(period.price()).isEqualTo(Money.of("10", "EUR")));
    }

    @Test
    void historyIsEmptyForBooksNeverPriced() {
        assertThat(pricing.findHistory(new BookId(209), Pageable.unpaged())).isEmpty();
    }

    @Test
    void databaseAllowsOnlyOneCurrentPricePerBook() {
        pricing.setPrice(new BookId(210), BigDecimal.TEN, null);

        // What two concurrent first prices would attempt after both passed the service's checks.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO book_price_period (book_id, amount, currency, valid_from) VALUES (210, 5, 'EUR', now())
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
