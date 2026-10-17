package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.AuthorIdType;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookIdType;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.ordering.OrderIdType;
import cz.codingmonkey.bookstore.shared.LongId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.Type;

/**
 * Records one change to a book, an author or an order (exactly one of them).
 */
@Entity
class AuditEntry {

    /** {@code ORDER_*} actions are {@code "ORDER_" + OrderStep}. */
    enum Action {
        BOOK_CREATED, BOOK_DELETED, STOCK_ADJUSTED, AUTHOR_CREATED, PRICE_CHANGED,
        ORDER_PLACED, ORDER_STOCK_RESERVED, ORDER_STOCK_MISSING, ORDER_PAYMENT_CHARGED, ORDER_PAYMENT_DECLINED,
        ORDER_CONFIRMED, ORDER_REJECTED, ORDER_CANCELLED, ORDER_STOCK_RELEASED, ORDER_PAYMENT_REFUNDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Type(AuditEntryIdType.class)
    private AuditEntryId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    /** Set for book entries. */
    @Type(BookIdType.class)
    private BookId bookId;

    /** Set for author entries. */
    @Type(AuthorIdType.class)
    private AuthorId authorId;

    /** Set for order entries. */
    @Type(OrderIdType.class)
    private OrderId orderId;

    @Column(nullable = false)
    private String description;

    /** Username of whoever made the change; {@code null} for changes without a signed-in user. */
    @Column(length = 100)
    private String actor;

    @Column(nullable = false)
    private Instant occurredAt;

    protected AuditEntry() {
    }

    private AuditEntry(Action action, BookId bookId, AuthorId authorId, OrderId orderId, String actor,
            String description, Instant occurredAt) {
        this.action = action;
        this.bookId = bookId;
        this.authorId = authorId;
        this.orderId = orderId;
        this.actor = actor;
        this.description = description;
        this.occurredAt = occurredAt;
    }

    /**
     * @param subject    the book, author or order id the entry is about
     * @param occurredAt when the change happened (order steps are recorded in parallel, so not in order)
     */
    static AuditEntry of(Action action, LongId subject, String actor, String description, Instant occurredAt) {
        return switch (subject) {
            case BookId bookId -> new AuditEntry(action, bookId, null, null, actor, description, occurredAt);
            case AuthorId authorId -> new AuditEntry(action, null, authorId, null, actor, description, occurredAt);
            case OrderId orderId -> new AuditEntry(action, null, null, orderId, actor, description, occurredAt);
            default -> throw new IllegalArgumentException("Cannot audit changes to " + subject);
        };
    }

    AuditEntryId getId() { return id; }
    Action getAction() { return action; }
    BookId getBookId() { return bookId; }
    AuthorId getAuthorId() { return authorId; }
    OrderId getOrderId() { return orderId; }
    String getDescription() { return description; }
    String getActor() { return actor; }
    Instant getOccurredAt() { return occurredAt; }
}
