package cz.codingmonkey.bookstore.shared;

import java.time.Instant;

/**
 * An event that is recorded in the audit trail. The event describes itself, so the audit module needs no knowledge of
 * the publishing module beyond the id types it stores.
 */
public interface Auditable {

    /** Name of the audit action, e.g. {@code "BOOK_CREATED"}; must be one the audit module knows. */
    String auditAction();

    /** What the change is about: a book, author or order id. */
    LongId auditSubject();

    /** Short human-readable text, e.g. {@code "Price changed from 10.00 EUR to 8.50 EUR"}. */
    String auditDescription();

    /**
     * Username of whoever made the change, captured at publish time ({@link CurrentUser}) because listeners run with
     * no signed-in user; {@code null} without a signed-in user.
     */
    String actor();

    /** When the change happened; by default when it is recorded. */
    default Instant occurredAt() {
        return Instant.now();
    }
}
