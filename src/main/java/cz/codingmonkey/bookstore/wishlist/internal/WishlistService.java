package cz.codingmonkey.bookstore.wishlist.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class WishlistService {

    /** The most books one customer can have on the list. */
    static final int LIMIT = 200;

    private static final ChangeLog changes = ChangeLog.of(WishlistService.class);

    private final WishlistItems items;
    private final BookCatalog books;

    WishlistService(WishlistItems items, BookCatalog books) {
        this.items = items;
        this.books = books;
    }

    /**
     * Puts the book on the customer's list; nothing changes if it is already there. The book stays locked against
     * deletion until the transaction commits, so a concurrent delete can't leave an entry for a missing book.
     * <p>
     * The limit is checked without a lock: two adds at once can take a list one past it, which is harmless.
     *
     * @throws UnknownBookException if there is no such book
     * @throws WishlistFullException if the list has {@link #LIMIT} other books
     */
    @Transactional
    void add(AccountId account, BookId book) {
        if (!books.existsAndLockAgainstDeletion(book)) {
            throw new UnknownBookException(book);
        }
        if (items.exists(account, book)) {
            return;
        }
        if (items.count(account) >= LIMIT) {
            throw new WishlistFullException();
        }
        // The column has microsecond precision.
        items.insert(account, book, Instant.now().truncatedTo(ChronoUnit.MICROS));
        changes.info("Account {} added book {} to the wishlist", account, book);
    }

    /** Takes the book off the list; nothing happens if it isn't there. */
    @Transactional
    void remove(AccountId account, BookId book) {
        if (items.delete(account, book) > 0) {
            changes.info("Account {} removed book {} from the wishlist", account, book);
        }
    }

    /** A deleted book leaves every wishlist. */
    @ApplicationModuleListener
    void on(BookDeletedEvent event) {
        int removed = items.deleteAll(event.bookId());
        if (removed > 0) {
            changes.info("Removed {} wishlist entries of deleted book {}", removed, event.bookId());
        }
    }
}
