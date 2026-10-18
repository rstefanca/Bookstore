package cz.codingmonkey.bookstore.ordering.internal;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorService;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookDeletionVetoedException;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookService;
import cz.codingmonkey.bookstore.customer.TestAddresses;
import cz.codingmonkey.bookstore.inventory.internal.InventoryService;
import cz.codingmonkey.bookstore.pricing.internal.PricingService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * Checks that checkout keeps a concurrent delete from removing a book that is being ordered.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CheckoutDeleteRaceTest {

    @Autowired
    BookService books;

    @Autowired
    AuthorService authors;

    @Autowired
    PricingService pricing;

    @Autowired
    InventoryService inventory;

    @Autowired
    CartService carts;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void deleteWaitsForInFlightCheckoutAndIsThenVetoed() {
        var author = authors.create(new AuthorRequest("Author", Set.of())).id();
        String isbn = "race-" + UUID.randomUUID().toString().substring(0, 8);
        BookId bookId = books.create(new BookRequest("Race", List.of(author), Set.of(), isbn, 2000, null)).id();
        pricing.setPrice(bookId, new BigDecimal("9.99"), null);
        inventory.restock(bookId, 1);
        String customer = "racer-" + UUID.randomUUID().toString().substring(0, 8);
        carts.setQuantity(customer, bookId, 1);
        CompletableFuture<Void> delete = new CompletableFuture<>();

        transaction.executeWithoutResult(status -> {
            carts.checkout(customer, new CheckoutRequest(TestAddresses.SHIPPING, TestAddresses.INVOICE)); // locks the book against deletion until this transaction commits

            CompletableFuture.runAsync(() -> books.delete(bookId))
                    .whenComplete((ok, error) -> {
                        if (error != null) delete.completeExceptionally(error);
                        else delete.complete(null);
                    });

            // Before the order is committed, the delete must be blocked on the lock rather than finish.
            sleep(Duration.ofMillis(500));
            assertThat(delete).isNotDone();
        });

        // Then the order (or, until it is reserved, the copy in stock) keeps the book.
        assertThat(delete).failsWithin(10, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .satisfies(cause -> assertThat(cause instanceof CompletionException ? cause.getCause() : cause)
                        .isInstanceOf(BookDeletionVetoedException.class));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
