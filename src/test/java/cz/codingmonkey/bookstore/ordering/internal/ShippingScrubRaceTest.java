package cz.codingmonkey.bookstore.ordering.internal;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestAccountIds;
import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.customer.TestAddresses;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks that scrubbing the shipping address of a deleted account is not undone by an order step that read the
 * order before the scrub, and that the step still succeeds. The step is simulated (a transaction that changes the
 * state like {@code OrderProcess} does) because the real handler runs asynchronously and can't be paused between
 * its read and its write.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ShippingScrubRaceTest {

    @Autowired
    OrderRepository orders;

    @Autowired
    OrderingAccountDeletion deletion;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aStaleOrderStepSucceedsWithoutWritingTheShippingAddressBack() throws Exception {
        AccountId customer = TestAccountIds.next();
        long order = awaitingPayment(customer);
        CountDownLatch stepHasReadTheOrder = new CountDownLatch(1);
        CountDownLatch scrubbed = new CountDownLatch(1);

        CompletableFuture<Void> step = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            CustomerOrder stale = orders.findById(new OrderId(order)).orElseThrow();
            stepHasReadTheOrder.countDown();
            await(scrubbed);
            stale.paid(); // the order step changes the state, Hibernate then writes the whole row
        }));

        await(stepHasReadTheOrder);
        deletion.anonymize(customer);
        scrubbed.countDown();

        assertThat(step).succeedsWithin(10, TimeUnit.SECONDS);
        assertShippingScrubbedInvoiceKept(order);
        assertThat(jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, order))
                .as("the stale step still applied").isEqualTo("CONFIRMED");
    }

    @Test
    void aScrubWaitsForAnOrderStepInFlightAndThenWins() throws Exception {
        AccountId customer = TestAccountIds.next();
        long order = awaitingPayment(customer);
        CompletableFuture<Void> scrub = new CompletableFuture<>();

        transaction.executeWithoutResult(status -> {
            CustomerOrder current = orders.findById(new OrderId(order))
                    .orElseThrow();
            current.paid();
            orders.flush(); // the step holds the order's row lock until it commits

            CompletableFuture.runAsync(() -> deletion.anonymize(customer)).whenComplete((ok, error) -> {
                if (error != null) scrub.completeExceptionally(error);
                else scrub.complete(null);
            });
            sleep(Duration.ofMillis(500));
            assertThat(scrub).isNotDone();
        });

        assertThat(scrub).succeedsWithin(10, TimeUnit.SECONDS);
        assertShippingScrubbedInvoiceKept(order);
        assertThat(jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, order))
                .isEqualTo("CONFIRMED");
    }

    private long awaitingPayment(AccountId customer) {
        CustomerOrder saved = orders.save(new CustomerOrder(customer,
                List.of(new OrderLine(new BookId(1), "Dune", 1,
                        BigDecimal.TEN)), Currency.getInstance("EUR"),
                TestAddresses.SHIPPING, TestAddresses.INVOICE));
        jdbc.update("UPDATE customer_order SET status = 'AWAITING_PAYMENT' WHERE id = ?", saved.getId().value());
        return saved.getId().value();
    }

    private void assertShippingScrubbedInvoiceKept(long order) {
        CustomerOrder found = orders.findById(new OrderId(order)).orElseThrow();
        assertThat(found.getShipping()).isNull();
        assertThat(found.getInvoice()).isEqualTo(TestAddresses.INVOICE);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
