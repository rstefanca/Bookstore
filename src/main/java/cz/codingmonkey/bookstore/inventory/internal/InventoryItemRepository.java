package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, BookId> {

    /**
     * {@code SELECT ... FOR UPDATE} in book id order, so two reservations needing the same books wait for each
     * other instead of deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<InventoryItem> findAndLockByBookIdInOrderByBookIdAsc(Collection<BookId> bookIds);
}
