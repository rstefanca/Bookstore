package cz.codingmonkey.bookstore.bookcatalog;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read access to the catalog for other modules.
 */
public interface BookCatalog {

    /** The book with the given id, if it exists. */
    Optional<BookDetails> findBook(BookId bookId);

    /** Books matching the search, in the page's order and size; use {@link Pageable#unpaged} for all. */
    Page<BookDetails> findBooks(BookSearch search, Pageable page);

    /**
     * Returns whether the book exists and, if so, keeps it from being deleted until the caller's transaction ends.
     * Must be called within a transaction.
     */
    boolean existsAndLockAgainstDeletion(BookId bookId);
}
