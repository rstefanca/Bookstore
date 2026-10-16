package cz.codingmonkey.bookstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorService;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookService;
import cz.codingmonkey.bookstore.bookcatalog.internal.GenreRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.GenreService;
import cz.codingmonkey.bookstore.inventory.internal.InventoryService;
import cz.codingmonkey.bookstore.pricing.internal.PricingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Data changes are logged at INFO through Log4j 2, only once committed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LoggingIntegrationTest {

    @Autowired
    BookService books;

    @Autowired
    AuthorService authors;

    @Autowired
    GenreService genres;

    @Autowired
    InventoryService inventory;

    @Autowired
    PricingService pricing;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void usesLog4j2() {
        assertThat(LogManager.getContext(false)).isInstanceOf(org.apache.logging.log4j.core.LoggerContext.class);
        assertThatThrownBy(() -> Class.forName("ch.qos.logback.classic.Logger"))
                .as("Logback is not on the classpath").isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void logsEveryChangeAtInfo(CapturedOutput output) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var genre = genres.create(new GenreRequest("Logged Genre " + suffix)).id();
        genres.update(genre, new GenreRequest("Renamed Genre " + suffix));
        var author = authors.create(new AuthorRequest("Logged Author", Set.of())).id();
        authors.update(author, new AuthorRequest("Renamed Author", Set.of(genre)));
        BookId book = books.create(
                new BookRequest("Logged Book", List.of(author), Set.of(), "log-" + suffix, 2000, null)).id();
        books.update(book, new BookRequest("Logged Book 2", List.of(author), Set.of(), "log-" + suffix, 2000, null));
        // Embeddings are computed asynchronously; wait, or the deletion below could overtake them.
        await().untilAsserted(() -> assertThat(output.getAll()).contains("Updated embedding of book " + book));
        inventory.restock(book, 3);
        inventory.withdraw(book, 3);
        pricing.setPrice(book, new BigDecimal("10"), null);
        pricing.setPrice(book, new BigDecimal("8"), null);
        pricing.removePrice(book);
        books.delete(book);
        authors.delete(author);
        genres.delete(genre);

        assertThat(output.getAll()).contains(
                "Created genre " + genre + " \"Logged Genre " + suffix + "\"",
                "Renamed genre " + genre,
                "Created author " + author + " \"Logged Author\"",
                "Updated author " + author + " \"Renamed Author\"",
                "Created book " + book + " \"Logged Book\" (ISBN log-" + suffix + ")",
                "Updated book " + book + " \"Logged Book 2\" (revision 1)",
                "Restocked book " + book + " by 3, now 3 in stock",
                "Withdrew 3 of book " + book + ", now 0 in stock",
                "Set price of book " + book + " to 10.00 EUR",
                "Changed price of book " + book + " from 10.00 EUR to 8.00 EUR",
                "Removed price of book " + book + " (was 8.00 EUR)",
                "Deleted book " + book,
                "Deleted author " + author,
                "Deleted genre " + genre);
        assertThat(output.getAll().lines().filter(line -> line.contains("Created book " + book)))
                .singleElement().asString().contains("INFO").contains("BookService");

        // Changes made by listeners in other modules, after the catalog transaction commits.
        await().untilAsserted(() -> assertThat(output.getAll()).contains(
                "Recorded audit entry BOOK_CREATED for book " + book,
                "Recorded audit entry AUTHOR_CREATED for author " + author,
                "Recorded audit entry PRICE_CHANGED for book " + book,
                "Removed embedding of deleted book " + book));
    }

    @Test
    void doesNotLogChangesThatRollBack(CapturedOutput output) {
        String name = "Rolled Back Author " + UUID.randomUUID();

        transaction.executeWithoutResult(status -> {
            authors.create(new AuthorRequest(name, Set.of()));
            status.setRollbackOnly();
        });

        assertThat(output.getAll()).doesNotContain(name);
    }
}
