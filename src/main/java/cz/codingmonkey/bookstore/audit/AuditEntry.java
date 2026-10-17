package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.shared.LongId;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;
import org.hibernate.annotations.Type;

/**
 * Records one change to one subject, any {@code LongId}.
 */
@Entity
class AuditEntry {

    /** Upper snake case, as {@code Auditable.auditAction()} promises; also the format of subject type names. */
    static final Pattern NAME_FORMAT = Pattern.compile("[A-Z][A-Z0-9_]*");
    static final int MAX_NAME_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Type(AuditEntryIdType.class)
    private AuditEntryId id;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String action;

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

    private AuditEntry(String action, AuditSubject subject, String actor, String description, Instant occurredAt) {
        this.action = action;
        this.subject = subject;
        this.actor = actor;
        this.description = description;
        this.occurredAt = occurredAt;
    }

    /**
     * @param action     upper snake case ({@code [A-Z][A-Z0-9_]*}), at most 50 characters
     * @param subject    what the change is about
     * @param occurredAt when the change happened (order steps are recorded in parallel, so not in order); truncated
     *                   to microseconds, the precision Postgres stores, so that an event delivered again matches
     *                   the entry stored for it
     * @throws IllegalArgumentException if the action or the subject's type name is malformed (same format)
     */
    static AuditEntry of(String action, LongId subject, String actor, String description, Instant occurredAt) {
        Objects.requireNonNull(subject, "subject");
        if (!isValidName(action)) {
            throw new IllegalArgumentException("Audit action must be upper snake case of at most "
                    + MAX_NAME_LENGTH + " characters, was '" + action + "'");
        }
        if (!isValidName(subject.typeName())) {
            throw new IllegalArgumentException("Audit subject type must be upper snake case of at most "
                    + MAX_NAME_LENGTH + " characters, was '" + subject.typeName() + "'");
        }
        return new AuditEntry(action, AuditSubject.of(subject), actor, description,
                occurredAt.truncatedTo(ChronoUnit.MICROS));
    }

    static boolean isValidName(String name) {
        return name.length() <= MAX_NAME_LENGTH && NAME_FORMAT.matcher(name).matches();
    }

    AuditEntryId getId() { return id; }
    String getAction() { return action; }
    AuditSubject getSubject() { return subject; }
    String getDescription() { return description; }
    String getActor() { return actor; }
    Instant getOccurredAt() { return occurredAt; }
}
