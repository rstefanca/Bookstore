package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.audit.AuditEntry.Action;
import cz.codingmonkey.bookstore.bookcatalog.AuthorCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.inventory.StockAdjustedEvent;
import cz.codingmonkey.bookstore.ordering.OrderProgressedEvent;
import cz.codingmonkey.bookstore.pricing.PriceChangedEvent;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Handles book, author, inventory and pricing events asynchronously after the publishing transaction commits.
 * Publications are tracked in the event publication registry, so events are not lost if a listener fails.
 */
@Component
class AuditListener {

    private static final ChangeLog changes = ChangeLog.of(AuditListener.class);

    private final AuditEntryRepository repository;

    AuditListener(AuditEntryRepository repository) {
        this.repository = repository;
    }

    @ApplicationModuleListener
    void on(BookCreatedEvent event) {
        record(AuditEntry.forBook(Action.BOOK_CREATED, event.bookId(), event.actor(),
                "Created \"%s\" by %s (ISBN %s)".formatted(event.title(), String.join(", ", event.authors()),
                        event.isbn())));
    }

    @ApplicationModuleListener
    void on(BookDeletedEvent event) {
        record(AuditEntry.forBook(Action.BOOK_DELETED, event.bookId(), event.actor(),
                "Deleted book with ISBN %s".formatted(event.isbn())));
    }

    @ApplicationModuleListener
    void on(StockAdjustedEvent event) {
        String change = event.delta() > 0 ? "Restocked %d".formatted(event.delta())
                : "Withdrew %d".formatted(-event.delta());
        record(AuditEntry.forBook(Action.STOCK_ADJUSTED, event.bookId(), event.actor(),
                "%s, now %d in stock%s".formatted(change, event.newQuantity(),
                        event.isOutOfStock() ? " (OUT OF STOCK)" : "")));
    }

    @ApplicationModuleListener
    void on(AuthorCreatedEvent event) {
        record(AuditEntry.forAuthor(Action.AUTHOR_CREATED, event.authorId(), event.actor(),
                "Created author \"%s\"".formatted(event.name())));
    }

    @ApplicationModuleListener
    void on(PriceChangedEvent event) {
        String description;
        if (event.oldPrice() == null) {
            description = "Price set to %s".formatted(event.newPrice());
        } else if (event.newPrice() == null) {
            description = "Price removed (was %s); no longer for sale".formatted(event.oldPrice());
        } else {
            description = "Price changed from %s to %s".formatted(event.oldPrice(), event.newPrice());
        }
        record(AuditEntry.forBook(Action.PRICE_CHANGED, event.bookId(), event.actor(), description));
    }

    /**
     * Every step of a purchase, as reported by the ordering module. Each step happens once per order, so a step
     * delivered again (retry after a crash) is skipped; the database enforces it too.
     */
    @ApplicationModuleListener
    void on(OrderProgressedEvent event) {
        Action action = Action.valueOf("ORDER_" + event.step().name());
        if (repository.existsByOrderIdAndAction(event.orderId(), action)) {
            return;
        }
        record(AuditEntry.forOrder(action, event.orderId(), event.actor(), event.detail(), event.occurredAt()));
    }

    private void record(AuditEntry entry) {
        repository.save(entry);
        if (entry.getOrderId() != null) {
            // Ids only: order entries carry the customer's username and book titles.
            changes.info("Recorded audit entry {} for order {}", entry.getAction(), entry.getOrderId());
            return;
        }
        changes.info("Recorded audit entry {} for {} {} by {}: {}", entry.getAction(),
                entry.getBookId() != null ? "book" : "author",
                entry.getBookId() != null ? entry.getBookId() : entry.getAuthorId(),
                entry.getActor() != null ? entry.getActor() : "system", entry.getDescription());
    }
}
