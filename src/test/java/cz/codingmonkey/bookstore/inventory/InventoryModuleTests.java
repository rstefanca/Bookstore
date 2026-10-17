package cz.codingmonkey.bookstore.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletionPolicy;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.inventory.internal.InsufficientStockException;
import cz.codingmonkey.bookstore.inventory.internal.InventoryItemRepository;
import cz.codingmonkey.bookstore.inventory.internal.InventoryService;
import cz.codingmonkey.bookstore.inventory.internal.UnknownBookException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Bootstraps only the inventory module; the book module's {@link BookCatalog} is mocked.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class InventoryModuleTests {

    @MockitoBean
    BookCatalog books;

    @Autowired
    InventoryService service;

    @Autowired
    InventoryItemRepository repository;

    @Autowired
    BookDeletionPolicy deletionPolicy;

    @BeforeEach
    void allBooksExist() {
        given(books.existsAndLockAgainstDeletion(any())).willReturn(true);
    }

    @Test
    void firstRestockCreatesStockRecord() {
        BookId bookId = new BookId(100);
        assertThat(repository.existsById(bookId)).isFalse();

        service.restock(bookId, 3);

        assertThat(service.findByBookId(bookId).quantity()).isEqualTo(3);
    }

    @Test
    void rejectsRestockOfUnknownBook() {
        BookId bookId = new BookId(101);
        given(books.existsAndLockAgainstDeletion(bookId)).willReturn(false);

        assertThatThrownBy(() -> service.restock(bookId, 1)).isInstanceOf(UnknownBookException.class);
        assertThat(repository.existsById(bookId)).isFalse();
    }

    @Test
    void publishesStockAdjustedOnRestockAndWithdraw(Scenario scenario) {
        BookId bookId = new BookId(102);
        given(books.findBook(bookId)).willReturn(Optional.of(
                new BookDetails(bookId, "Dune", List.of(), List.of(), "isbn", null, null)));
        scenario.stimulate(() -> service.restock(bookId, 5))
                .andWaitForEventOfType(StockAdjustedEvent.class)
                .matching(event -> event.bookId().equals(bookId))
                .toArriveAndVerify(event -> {
                    assertThat(event.title()).as("for the audit log").isEqualTo("Dune");
                    assertThat(event.delta()).isEqualTo(5);
                    assertThat(event.newQuantity()).isEqualTo(5);
                });

        scenario.stimulate(() -> service.withdraw(bookId, 5))
                .andWaitForEventOfType(StockAdjustedEvent.class)
                .matching(event -> event.bookId().equals(bookId) && event.delta() < 0)
                .toArriveAndVerify(event -> {
                    assertThat(event.title()).isEqualTo("Dune");
                    assertThat(event.isOutOfStock()).isTrue();
                });
    }

    @Test
    void rejectsWithdrawalBeyondStock() {
        BookId bookId = new BookId(103);
        service.restock(bookId, 2);

        assertThatThrownBy(() -> service.withdraw(bookId, 3)).isInstanceOf(InsufficientStockException.class);
        assertThat(service.findByBookId(bookId).quantity()).isEqualTo(2);
    }

    @Test
    void vetoesDeletionOnlyWhileInStock() {
        BookId bookId = new BookId(104);
        assertThat(deletionPolicy.vetoReason(bookId)).as("never stocked").isEmpty();

        service.restock(bookId, 1);
        assertThat(deletionPolicy.vetoReason(bookId)).as("in stock").isPresent();

        service.withdraw(bookId, 1);
        assertThat(deletionPolicy.vetoReason(bookId)).as("sold out").isEmpty();
    }

    @Test
    void removesEmptyStockRecordOfDeletedBook(Scenario scenario) {
        BookId bookId = new BookId(105);
        service.restock(bookId, 1);
        service.withdraw(bookId, 1);

        scenario.publish(new BookDeletedEvent(bookId, "Neuromancer", "9780441569595", null))
                .andWaitForStateChange(() -> repository.existsById(bookId), exists -> !exists)
                .andVerify(exists -> assertThat(exists).isFalse());
    }
}
