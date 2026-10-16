package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.inventory.StockAdjustedEvent;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import cz.codingmonkey.bookstore.shared.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    private static final ChangeLog changes = ChangeLog.of(InventoryService.class);

    private final InventoryItemRepository repository;
    private final BookCatalog books;
    private final ApplicationEventPublisher events;

    public InventoryService(InventoryItemRepository repository, BookCatalog books, ApplicationEventPublisher events) {
        this.repository = repository;
        this.books = books;
        this.events = events;
    }

    public Page<InventoryResponse> findAll(Pageable page) {
        return repository.findAll(page).map(InventoryResponse::from);
    }

    public InventoryResponse findByBookId(BookId bookId) {
        return InventoryResponse.from(get(bookId));
    }

    /**
     * Adds stock, creating the book's stock record on its first restock. The book stays locked against deletion
     * until this transaction commits, so a concurrent delete cannot remove a book that is being restocked.
     */
    @Transactional
    public InventoryResponse restock(BookId bookId, int amount) {
        if (!books.existsAndLockAgainstDeletion(bookId)) {
            throw new UnknownBookException(bookId);
        }
        InventoryItem item = repository.findById(bookId).orElseGet(() -> new InventoryItem(bookId));
        item.restock(amount);
        repository.save(item);
        events.publishEvent(new StockAdjustedEvent(bookId, amount, item.getQuantity(), CurrentUser.username()));
        changes.info("Restocked book {} by {}, now {} in stock", bookId, amount, item.getQuantity());
        return InventoryResponse.from(item);
    }

    @Transactional
    public InventoryResponse withdraw(BookId bookId, int amount) {
        InventoryItem item = get(bookId);
        item.withdraw(amount);
        events.publishEvent(new StockAdjustedEvent(bookId, -amount, item.getQuantity(), CurrentUser.username()));
        changes.info("Withdrew {} of book {}, now {} in stock", amount, bookId, item.getQuantity());
        return InventoryResponse.from(item);
    }

    boolean isInStock(BookId bookId) {
        return repository.findById(bookId).map(InventoryItem::isInStock).orElse(false);
    }

    /** Removes the (necessarily empty) stock record of a deleted book. */
    @ApplicationModuleListener
    void on(BookDeletedEvent event) {
        repository.findById(event.bookId()).ifPresent(item -> {
            repository.delete(item);
            changes.info("Removed stock record of deleted book {}", event.bookId());
        });
    }

    private InventoryItem get(BookId bookId) {
        return repository.findById(bookId).orElseThrow(() -> new InventoryItemNotFoundException(bookId));
    }
}
