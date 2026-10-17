package cz.codingmonkey.bookstore.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.Type;

/**
 * Records one change to its subject: a book, an author or an order.
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

    @Embedded
    private AuditSubject subject;

    @Column(nullable = false)
    private String description;

    /** Username of whoever made the change; {@code null} for changes without a signed-in user. */
    @Column(length = 100)
    private String actor;

    @Column(nullable = false)
    private Instant occurredAt;

    protected AuditEntry() {
    }

    private AuditEntry(AuditSubject subject, Action action, String actor, String description, Instant occurredAt) {
        this.subject = subject;
        this.action = action;
        this.actor = actor;
        this.description = description;
        this.occurredAt = occurredAt;
    }

    static AuditEntry about(AuditSubject subject, Action action, String actor, String description) {
        return about(subject, action, actor, description, Instant.now());
    }

    /** @param occurredAt when it happened, for changes recorded out of order (steps of an order) */
    static AuditEntry about(AuditSubject subject, Action action, String actor, String description,
            Instant occurredAt) {
        return new AuditEntry(subject, action, actor, description, occurredAt);
    }

    AuditEntryId getId() { return id; }
    AuditSubject getSubject() { return subject; }
    Action getAction() { return action; }
    String getDescription() { return description; }
    String getActor() { return actor; }
    Instant getOccurredAt() { return occurredAt; }
}
