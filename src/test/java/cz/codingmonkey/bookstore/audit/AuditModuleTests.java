package cz.codingmonkey.bookstore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.audit.AuditEntry.Action;
import cz.codingmonkey.bookstore.bookcatalog.AuthorCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.inventory.StockAdjustedEvent;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.ordering.OrderProgressedEvent;
import cz.codingmonkey.bookstore.ordering.OrderStep;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.pricing.PriceChangedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

/**
 * Bootstraps only the audit module and drives it purely through the other modules' published events.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class AuditModuleTests {

    @Autowired
    AuditEntryRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recordsBookCreated(Scenario scenario) {
        BookId bookId = new BookId(42);
        var event = new BookCreatedEvent(bookId, "Dune", List.of("Frank Herbert"), List.of(), "9780441013593", null,
                0, "jane");
        scenario.publish(event)
                .andWaitForStateChange(() -> repository.findByBookIdOrderByOccurredAtDesc(bookId),
                        list -> !list.isEmpty())
                .andVerify(entries -> {
                    assertThat(entries).singleElement().satisfies(entry -> {
                        assertThat(entry.getAction()).isEqualTo(Action.BOOK_CREATED);
                        assertThat(entry.getActor()).isEqualTo("jane");
                        assertThat(entry.getDescription()).contains("Dune", "Frank Herbert");
                    });
                });
    }

    @Test
    void recordsBookDeleted(Scenario scenario) {
        BookId bookId = new BookId(43);
        scenario.publish(new BookDeletedEvent(bookId, "9780441569595", null))
                .andWaitForStateChange(() -> repository.findByBookIdOrderByOccurredAtDesc(bookId),
                        list -> !list.isEmpty())
                .andVerify(entries -> assertThat(entries).singleElement()
                        .extracting(AuditEntry::getAction).isEqualTo(Action.BOOK_DELETED));
    }

    @Test
    void recordsStockAdjusted(Scenario scenario) {
        BookId bookId = new BookId(44);
        scenario.publish(new StockAdjustedEvent(bookId, -3, 0, null))
                .andWaitForStateChange(() -> repository.findByBookIdOrderByOccurredAtDesc(bookId),
                        list -> !list.isEmpty())
                .andVerify(entries -> assertThat(entries).singleElement().satisfies(entry -> {
                    assertThat(entry.getAction()).isEqualTo(Action.STOCK_ADJUSTED);
                    assertThat(entry.getDescription()).isEqualTo("Withdrew 3, now 0 in stock (OUT OF STOCK)");
                }));
    }

    @Test
    void recordsChangesWithoutASignedInUserWithoutActor(Scenario scenario) {
        BookId bookId = new BookId(47);
        scenario.publish(new StockAdjustedEvent(bookId, 5, 5, null))
                .andWaitForStateChange(() -> repository.findByBookIdOrderByOccurredAtDesc(bookId),
                        list -> !list.isEmpty())
                .andVerify(entries -> assertThat(entries).singleElement()
                        .extracting(AuditEntry::getActor).isNull());
    }

    @Test
    void recordsAuthorCreated(Scenario scenario) {
        AuthorId authorId = new AuthorId(45);
        scenario.publish(new AuthorCreatedEvent(authorId, "Ursula K. Le Guin", null))
                .andWaitForStateChange(() -> repository.findByAuthorIdOrderByOccurredAtDesc(authorId),
                        list -> !list.isEmpty())
                .andVerify(entries -> assertThat(entries).singleElement().satisfies(entry -> {
                    assertThat(entry.getAction()).isEqualTo(Action.AUTHOR_CREATED);
                    assertThat(entry.getBookId()).isNull();
                    assertThat(entry.getDescription()).isEqualTo("Created author \"Ursula K. Le Guin\"");
                }));
    }

    @Test
    void databaseRequiresExactlyOneSubject() {
        String insert = """
                INSERT INTO audit_entry (action, book_id, author_id, description, occurred_at)
                VALUES ('BOOK_CREATED', ?, ?, 'x', now())
                """;
        assertThatThrownBy(() -> jdbc.update(insert, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, 1L, 1L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO audit_entry (action, book_id, order_id, description, occurred_at)
                VALUES ('ORDER_PLACED', 1, 1, 'x', now())
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recordsOrderSteps(Scenario scenario) {
        OrderId orderId = new OrderId(48);
        Instant declinedAt = Instant.parse("2026-10-16T08:00:00.123456Z");
        scenario.publish(new OrderProgressedEvent(orderId, OrderStep.PAYMENT_DECLINED, "Declined", "rita", declinedAt))
                .andWaitForStateChange(() -> repository.findByOrderIdOrderByOccurredAtDesc(orderId),
                        list -> !list.isEmpty())
                .andVerify(entries -> assertThat(entries).singleElement().satisfies(entry -> {
                    assertThat(entry.getAction()).isEqualTo(Action.ORDER_PAYMENT_DECLINED);
                    assertThat(entry.getOrderId()).isEqualTo(orderId);
                    assertThat(entry.getBookId()).isNull();
                    assertThat(entry.getActor()).isEqualTo("rita");
                    assertThat(entry.getDescription()).isEqualTo("Declined");
                    assertThat(entry.getOccurredAt()).as("when it happened, not when it was recorded")
                            .isEqualTo(declinedAt);
                }));
    }

    @Test
    void aRepeatedOrderStepIsRecordedOnce(Scenario scenario) {
        // Events are delivered again after a crash between the listener's commit and marking the event done.
        OrderId orderId = new OrderId(50);
        var event = new OrderProgressedEvent(orderId, OrderStep.PLACED, "1 copies, 9.99 EUR", "rita", Instant.now());
        scenario.publish(event)
                .andWaitForStateChange(() -> repository.findByOrderIdOrderByOccurredAtDesc(orderId),
                        list -> !list.isEmpty());

        scenario.publish(event).andWaitForStateChange(() -> true);

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(repository.findByOrderIdOrderByOccurredAtDesc(orderId)).hasSize(1));
    }

    @Test
    void databaseRecordsEachOrderStepOnce() {
        String insert = """
                INSERT INTO audit_entry (action, order_id, description, occurred_at)
                VALUES ('ORDER_PLACED', 51, 'x', now())
                """;
        jdbc.update(insert);
        assertThatThrownBy(() -> jdbc.update(insert)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void orderStepsAreLoggedWithIdsOnly(Scenario scenario, CapturedOutput output) {
        OrderId orderId = new OrderId(52);
        scenario.publish(new OrderProgressedEvent(orderId, OrderStep.STOCK_MISSING, "Not enough stock: Sapiens",
                        "rita@example.com", Instant.now()))
                .andWaitForStateChange(() -> repository.findByOrderIdOrderByOccurredAtDesc(orderId),
                        list -> !list.isEmpty());

        await().untilAsserted(() -> assertThat(output.getAll())
                .contains("Recorded audit entry ORDER_STOCK_MISSING for order 52"));
        assertThat(output.getAll()).doesNotContain("rita@example.com", "Sapiens");
    }

    @Test
    void recordsPriceChanges(Scenario scenario) {
        BookId bookId = new BookId(46);
        Money ten = Money.of("10", "EUR");
        Money discounted = Money.of("8.5", "EUR");

        publishPriceChange(scenario, new PriceChangedEvent(bookId, null, ten, null), 1);
        publishPriceChange(scenario, new PriceChangedEvent(bookId, ten, discounted, null), 2);
        publishPriceChange(scenario, new PriceChangedEvent(bookId, discounted, null, null), 3);

        assertThat(repository.findByBookIdOrderByOccurredAtDesc(bookId))
                .allSatisfy(entry -> assertThat(entry.getAction()).isEqualTo(Action.PRICE_CHANGED))
                .extracting(AuditEntry::getDescription)
                .containsExactlyInAnyOrder(
                        "Price set to 10.00 EUR",
                        "Price changed from 10.00 EUR to 8.50 EUR",
                        "Price removed (was 8.50 EUR); no longer for sale");
    }

    private void publishPriceChange(Scenario scenario, PriceChangedEvent event, int expectedEntries) {
        scenario.publish(event)
                .andWaitForStateChange(() -> repository.findByBookIdOrderByOccurredAtDesc(event.bookId()),
                        entries -> entries.size() == expectedEntries)
                .andVerify(entries -> assertThat(entries).hasSize(expectedEntries));
    }
}
