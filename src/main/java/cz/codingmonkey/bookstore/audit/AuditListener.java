package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.audit.AuditEntry.Action;
import cz.codingmonkey.bookstore.bookcatalog.AuthorCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
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
        record(AuditEntry.about(AuditSubject.of(event.bookId()), Action.BOOK_CREATED, event.actor(),
                "Created \"%s\" by %s (ISBN %s)".formatted(event.title(), String.join(", ", event.authors()),
                        event.isbn())));
    }

    @ApplicationModuleListener
    void on(BookDeletedEvent event) {
        record(AuditEntry.about(AuditSubject.of(event.bookId()), Action.BOOK_DELETED, event.actor(),
                "Deleted %s with ISBN %s".formatted(book(event.bookId(), event.title()), event.isbn())));
    }

    @ApplicationModuleListener
    void on(StockAdjustedEvent event) {
        String book = book(event.bookId(), event.title());
        String change = event.delta() > 0 ? "Restocked %s by %d".formatted(book, event.delta())
                : "Withdrew %d of %s".formatted(-event.delta(), book);
        record(AuditEntry.about(AuditSubject.of(event.bookId()), Action.STOCK_ADJUSTED, event.actor(),
                "%s, now %d in stock%s".formatted(change, event.newQuantity(),
                        event.isOutOfStock() ? " (OUT OF STOCK)" : "")));
    }

    @ApplicationModuleListener
    void on(AuthorCreatedEvent event) {
        record(AuditEntry.about(AuditSubject.of(event.authorId()), Action.AUTHOR_CREATED, event.actor(),
                "Created author \"%s\"".formatted(event.name())));
    }

    @ApplicationModuleListener
    void on(PriceChangedEvent event) {
        String book = book(event.bookId(), event.title());
        String description;
        if (event.oldPrice() == null) {
            description = "Price of %s set to %s".formatted(book, event.newPrice());
        } else if (event.newPrice() == null) {
            description = "Price of %s removed (was %s); no longer for sale".formatted(book, event.oldPrice());
        } else {
            description = "Price of %s changed from %s to %s".formatted(book, event.oldPrice(), event.newPrice());
        }
        record(AuditEntry.about(AuditSubject.of(event.bookId()), Action.PRICE_CHANGED, event.actor(), description));
    }

    /**
     * Every step of a purchase, as reported by the ordering module. Each step happens once per order, so a step
     * delivered again (retry after a crash) is skipped; the database enforces it too.
     */
    @ApplicationModuleListener
    void on(OrderProgressedEvent event) {
        Action action = Action.valueOf("ORDER_" + event.step().name());
        AuditSubject order = AuditSubject.of(event.orderId());
        if (repository.existsBySubjectAndAction(order, action)) {
            return;
        }
        record(AuditEntry.about(order, action, event.actor(), event.detail(), event.occurredAt()));
    }

    /** Names a book for an auditor; events published before they carried the title name it by id only. */
    private static String book(BookId bookId, String title) {
        return title == null ? "book %s".formatted(bookId) : "\"%s\" (book %s)".formatted(title, bookId);
    }

    private void record(AuditEntry entry) {
        repository.save(entry);
        if (entry.getSubject().type() == AuditSubject.Type.ORDER) {
            // Ids only: order entries carry the customer's username and book titles.
            changes.info("Recorded audit entry {} for {}", entry.getAction(), entry.getSubject());
            return;
        }
        changes.info("Recorded audit entry {} for {} by {}: {}", entry.getAction(), entry.getSubject(),
                entry.getActor() != null ? entry.getActor() : "system", entry.getDescription());
    }
}
