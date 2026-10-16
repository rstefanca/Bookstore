package cz.codingmonkey.bookstore.bookcatalog;

import java.util.Optional;

/**
 * Extension point for other modules to prevent a book from being deleted. Implementations are consulted inside the
 * deleting transaction while the book row is locked, so their answer cannot be invalidated by a concurrent change
 * that goes through {@link BookCatalog#existsAndLockAgainstDeletion(BookId)}.
 */
public interface BookDeletionPolicy {

    /**
     * @return the reason the book must not be deleted, or empty if deletion is fine
     */
    Optional<String> vetoReason(BookId bookId);
}
