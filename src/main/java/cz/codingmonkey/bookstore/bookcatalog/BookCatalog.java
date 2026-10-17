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

    /**
     * Books matching the search, in the page's size; use {@link Pageable#unpaged} for all. The order is the page's
     * sort, unless the search has its own order, which then replaces the sort.
     */
    Page<BookDetails> findBooks(BookSearch search, Pageable page);

    /**
     * Returns whether the book exists and, if so, keeps it from being deleted until the caller's transaction ends.
     * Edits of the book are not held up. Must be called within a transaction.
     */
    boolean existsAndLockAgainstDeletion(BookId bookId);
}
