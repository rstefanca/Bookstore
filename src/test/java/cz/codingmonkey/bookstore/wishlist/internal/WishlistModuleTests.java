package cz.codingmonkey.bookstore.wishlist.internal;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestAccountIds;
import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.shared.AccountId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Only the wishlist module runs; the catalog is mocked.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class WishlistModuleTests {

    static final BookId DUNE = new BookId(1);
    static final BookId NEUROMANCER = new BookId(2);

    static final AccountId A = TestAccountIds.next();
    static final AccountId B = TestAccountIds.next();

    @MockitoBean
    BookCatalog catalog;

    @Autowired
    WishlistItems items;

    @Autowired
    WishlistAccountDeletion deletion;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM wishlist_item");
    }

    @Test
    void bookDeletionRemovesItsEntries(Scenario scenario) {
        add(A, DUNE);
        add(B, DUNE);
        add(A, NEUROMANCER);

        scenario.publish(deleted(DUNE)).andWaitForStateChange(() -> countOf(DUNE), count -> count == 0);

        assertThat(countOf(NEUROMANCER)).isEqualTo(1);
    }

    @Test
    void bookDeletionDeliveredTwiceIsHarmless(Scenario scenario) {
        add(A, DUNE);
        scenario.publish(deleted(DUNE)).andWaitForStateChange(() -> countOf(DUNE), count -> count == 0);

        scenario.publish(deleted(DUNE)).andWaitForStateChange(() -> countOf(DUNE), count -> count == 0);

        assertThat(countOf(DUNE)).isZero();
    }

    @Test
    void aWishlistIsNotARecordToKeep() {
        add(A, DUNE);

        assertThat(deletion.holdsRecords(A)).isFalse();
    }

    @Test
    void accountDeletionRemovesTheWishlist() {
        add(A, DUNE);
        add(A, NEUROMANCER);
        add(B, DUNE);

        deletion.removePersonalData(A);

        assertThat(items.count(A)).isZero();
        assertThat(items.count(B)).isEqualTo(1);
    }

    private void add(AccountId account, BookId book) {
        items.insert(account, book, java.time.Instant.now());
    }

    private int countOf(BookId book) {
        return jdbc.queryForObject("SELECT count(*) FROM wishlist_item WHERE book_id = ?", Integer.class,
                book.value());
    }

    private static BookDeletedEvent deleted(BookId book) {
        return new BookDeletedEvent(book, "9780441013593", TestAccountIds.next());
    }
}
