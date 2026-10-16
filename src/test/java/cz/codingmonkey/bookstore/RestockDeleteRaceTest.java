package cz.codingmonkey.bookstore;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorService;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookDeletionVetoedException;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookService;
import cz.codingmonkey.bookstore.inventory.internal.InventoryService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks that the book row lock keeps a concurrent delete from removing a book that is being restocked.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RestockDeleteRaceTest {

    @Autowired
    BookService books;

    @Autowired
    AuthorService authors;

    @Autowired
    InventoryService inventory;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void deleteWaitsForInFlightRestockAndIsThenVetoed() throws Exception {
        var author = authors.create(new AuthorRequest("Author", Set.of())).id();
        BookId bookId = books.create(
                new BookRequest("Race", List.of(author), Set.of(), "9780000000001", 2000, null)).id();
        CompletableFuture<Void> delete = new CompletableFuture<>();

        transaction.executeWithoutResult(status -> {
            inventory.restock(bookId, 1); // holds the share lock until this transaction commits

            CompletableFuture.runAsync(() -> books.delete(bookId))
                    .whenComplete((ok, error) -> {
                        if (error != null) delete.completeExceptionally(error);
                        else delete.complete(null);
                    });

            // Before the restock commits, the delete must be blocked on the lock rather than finish.
            sleep(Duration.ofMillis(500));
            assertThat(delete).isNotDone();
        });

        assertThat(delete).failsWithin(10, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .satisfies(cause -> assertThat(cause instanceof CompletionException ? cause.getCause() : cause)
                        .isInstanceOf(BookDeletionVetoedException.class));
        assertThat(inventory.findByBookId(bookId).quantity()).isEqualTo(1);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
