# Design: generic audit subject

Status: implemented (2026-10-17).

## Goal

Every audit entry is about exactly one thing, its subject. Today that is a book, an author or an order, each in its
own column (`book_id`, `author_id`, `order_id`), and the audit module imports `BookId`, `AuthorId`, `OrderId` and
lists every domain action in `AuditEntry.Action`. Instead:

- an entry stores its subject as any `shared.LongId`, kept as a type name plus the id value, so a new kind of
  subject needs no column, no migration and no API change;
- the audit module depends **only on `shared`** (since account deletion also on `security`'s public API, which is unrelated to audit actions): no imports of other modules, no domain action names, no
  order-specific rules.

Agreed with the user:
- Only the subject changes. Demo-data actors (`CurrentUser.runAs`) and book titles in descriptions from PR #1 are
  not part of this change; this branch starts from `main` and PR #1 is left as it is.
- The type name comes from `LongId.typeName()`, derived from the class name and pinned by a test.
- The action is stored as the event gives it, checked only for format.
- A redelivered event is skipped when an entry with the same subject, action and `occurredAt` exists. No handling
  of two deliveries at the same time (no unique index).
- Every audit log line names only the action and the subject.
- `/api/audit` changes in a **breaking** way to `subjectType`/`subjectId` filters and a `subject` field.

Unchanged: which events are audited and their descriptions and actors, who may read the audit log, paging.

## 1. `shared`

### `LongId.typeName()`

```java
/**
 * Name of this id's type, e.g. "BOOK" for BookId: the class name without a trailing "Id", in upper snake case.
 * Stored in the audit log, so renaming an id class changes it (TypeNamesTest pins the stored names).
 */
default String typeName() {
    String name = getClass().getSimpleName();
    if (name.endsWith("Id")) {
        name = name.substring(0, name.length() - 2);
    }
    return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
}
```

The current ids give `ACCOUNT`, `AUDIT_ENTRY`, `AUTHOR`, `BOOK`, `GENRE`, `ORDER`, `PAYMENT_REFERENCE`,
`PRICE_PERIOD` and `RESERVATION`. No id record changes.

### `Auditable`

Only the Javadoc changes:
- `auditAction()`: upper snake case (`[A-Z][A-Z0-9_]*`), at most 50 characters; the audit module rejects anything
  else.
- `auditSubject()`: any `LongId`; stored with its `typeName()`.
- `occurredAt()`: an event that must not be recorded twice when delivered again sets its own time at publish
  time (as `OrderProgressedEvent` does); the default (now, when recorded) differs on every delivery.

## 2. Schema: `V23__audit_entry_subject.sql`

```sql
-- An audit entry is about one subject of any type (LongId.typeName()), so a new kind needs no new column.
ALTER TABLE audit_entry ADD COLUMN subject_type VARCHAR(50);
ALTER TABLE audit_entry ADD COLUMN subject_id   BIGINT;
UPDATE audit_entry SET
    subject_type = CASE WHEN book_id   IS NOT NULL THEN 'BOOK'
                        WHEN author_id IS NOT NULL THEN 'AUTHOR'
                        ELSE 'ORDER' END,
    subject_id   = coalesce(book_id, author_id, order_id);
ALTER TABLE audit_entry ALTER COLUMN subject_type SET NOT NULL;
ALTER TABLE audit_entry ALTER COLUMN subject_id   SET NOT NULL;

DROP INDEX audit_entry_order_step_once;
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_one_subject;
ALTER TABLE audit_entry DROP COLUMN book_id, DROP COLUMN author_id, DROP COLUMN order_id;

-- "Everything about book 2" (which had no index before), and the check for an event delivered again.
CREATE INDEX audit_entry_subject_idx ON audit_entry (subject_type, subject_id, action, occurred_at);
```

- `audit_entry_one_subject` guarantees exactly one id per existing row, so `SET NOT NULL` cannot fail.
- No check constraint on `subject_type`: the audit module does not know the possible values.
- The database no longer rejects a second entry for the same order step; the listener's check (section 3) is the
  only guard.
- Flyway runs the migration in one transaction. It is one-way: application versions before it can't read the table
  (`ddl-auto: validate`).
- Existing migrations are not edited.

## 3. Audit module

### Dependencies

`package-info.java`: `@ApplicationModule(displayName = "Audit", allowedDependencies = "shared")`, so
`ModularityTests` fails if audit imports another module again.

### `AuditSubject` (new, package-private)

```java
/** What an audit entry is about: any LongId, kept as its type name and value. */
@Embeddable
record AuditSubject(
        @Column(name = "subject_type", nullable = false, length = 50) String typeName,
        @Column(name = "subject_id", nullable = false) long value) implements LongId {

    static AuditSubject of(LongId id) {
        return new AuditSubject(id.typeName(), id.value());
    }

    /** "book 2", "order 52". */
    @Override
    public String toString() {
        return typeName.toLowerCase(Locale.ROOT) + " " + value;
    }
}
```

The record accessors implement `LongId`; `typeName()` returns the stored name instead of deriving it from
`AuditSubject`'s own class name.

### `AuditEntry`

- `Action` enum and the `bookId`, `authorId`, `orderId` fields and getters are removed.
- `String action` (`length = 50`, not null) and `@Embedded AuditSubject subject` with `getSubject()`.
- `static AuditEntry of(String action, LongId subject, String actor, String description, Instant occurredAt)`:
  - throws `IllegalArgumentException` if `action` does not match `[A-Z][A-Z0-9_]*` or is longer than 50 characters;
  - stores `AuditSubject.of(subject)`;
  - truncates `occurredAt` to microseconds, the precision Postgres stores, so that a redelivered event (whose
    `Instant` may carry nanoseconds) matches the stored entry.

### `AuditEntryRepository`

- `Page<AuditEntry> findAllBy(Pageable page)` (unchanged)
- `Page<AuditEntry> findBySubject(AuditSubject subject, Pageable page)`
- `List<AuditEntry> findBySubjectOrderByOccurredAtDesc(AuditSubject subject)` (tests)
- `boolean existsBySubjectAndActionAndOccurredAt(AuditSubject subject, String action, Instant occurredAt)`

The per-column methods are removed.

### `AuditListener`

```java
@ApplicationModuleListener
void on(Auditable event) {
    var entry = AuditEntry.of(event.auditAction(), event.auditSubject(), event.actor(),
            event.auditDescription(), event.occurredAt());
    if (repository.existsBySubjectAndActionAndOccurredAt(entry.getSubject(), entry.getAction(),
            entry.getOccurredAt())) {
        return; // the same event delivered again
    }
    repository.save(entry);
    changes.info("Recorded audit entry {} for {}", entry.getAction(), entry.getSubject());
}
```

- `occurredAt()` is called once, so the default (now) is the same for the check and the entry.
- Only events that set their own `occurredAt` are deduplicated; today that is `OrderProgressedEvent`, so order steps
  are still recorded once and book/author events behave as before.
- An invalid action throws; the publication stays incomplete and is retried on restart, as with
  `Action.valueOf` today.
- The log line never contains the actor or description (order entries carry the customer's username and book
  titles; for other entries the publishing module already logs the change through its own `ChangeLog`).

## 4. HTTP API: `GET /api/audit` (admin only, as before)

### Request

- No parameters: all entries.
- `subjectType` + `subjectId`: entries about one subject, e.g. `?subjectType=BOOK&subjectId=2`.
  - `subjectType` must match `[A-Z][A-Z0-9_]*`, otherwise 400. A well-formed type nothing was recorded for returns
    an empty page (the audit module does not know the possible types).
  - `subjectId` must be positive (`LongId.requirePositive`), otherwise 400.
  - Only one of the two: 400, "Filter by both subjectType and subjectId".
- `bookId`, `authorId` or `orderId`: 400, "Use subjectType and subjectId". Spring ignores unknown parameters, so
  without this an old client would silently get the whole log.
- Paging unchanged: newest first (`occurredAt` desc, then `id` desc), `sort` ignored, size default 20, max 100.

### Response entry

```json
{ "id": 90,
  "action": "PRICE_CHANGED",
  "subject": { "type": "BOOK", "id": 2 },
  "actor": "alice",
  "description": "Price set to 12.99 EUR",
  "occurredAt": "2026-10-17T07:01:43.925731Z" }
```

`AuditEntryResponse(AuditEntryId id, String action, SubjectResponse subject, String actor, String description,
Instant occurredAt)` with `record SubjectResponse(String type, long id)` in `AuditController`: the API does not
serialize the JPA embeddable. The fields `bookId`, `authorId`, `orderId` are gone.

`SecurityConfiguration` is unchanged (same path and role).

## 5. Tests

New:
- `TypeNamesTest` (root package, plain unit test, next to `ModularityTests` because it names ids of several
  modules): pins `typeName()` of the seven public id types (`AuditEntryId` and `PricePeriodId` are package-private and
  never reach the audit module); its message says that renaming an id changes stored audit data.
- `AuditSubjectMigrationTest` (like `AuditOrderMigrationTest`): migrate to V22, insert one book, one author and one
  order entry with raw SQL, migrate to V23; each row has the expected `subject_type`/`subject_id`.
- `AuditControllerIntegrationTest`: `?subjectType=BOOK&subjectId=N` returns only that book's entries with
  `subject: {type: BOOK, id: N}`; only one of the two parameters: 400; malformed `subjectType` (`book`, `B-1`): 400;
  well-formed unknown type: empty page; `subjectId=0`: 400; `?bookId=1`: 400.

Rewritten: `AuditModuleTests` publishes test-only `Auditable` records about a test-only `LongId`, so it imports
no other module. It covers:
- an entry stores the subject's type name and value, action, actor, description and time;
- a malformed or too long action is not recorded;
- the same event delivered twice (same `occurredAt`, including nanoseconds) gives one entry; the same action at a
  different `occurredAt` gives two;
- the log line is `Recorded audit entry ORDER_STOCK_MISSING for order 52`, without description or actor;
- the raw-SQL database guards for the old columns (exactly one subject, order step once) are removed.

Changed (audit filters become `?subjectType=...&subjectId=...`): `AuditActorIntegrationTest`,
`BookControllerIntegrationTest`, `AuthorControllerIntegrationTest`, `InventoryControllerIntegrationTest`,
`PricingControllerIntegrationTest`, `OrderingIntegrationTest`. The "two filters at once: 400" checks in
`AuthorControllerIntegrationTest` and `OrderingIntegrationTest` are removed; `AuditControllerIntegrationTest`
covers the filter rules.

Unaffected: `AuditOrderMigrationTest` (migrates only to V21), `LoggingIntegrationTest` (checks
`Recorded audit entry BOOK_CREATED for book N`), `SecurityIntegrationTest` and `CustomerSignUpIntegrationTest`
(unfiltered `/api/audit`). `ModularityTests` now also enforces audit's single dependency.

## 6. Docs

- README: the purchase-audit paragraph (`GET /api/audit?orderId=N`, log lines), the audit section and the
  `/api/audit` row of the API table.
- CLAUDE.md: "a new action also needs a value in `AuditEntry.Action`" becomes the action format rule; mention that
  the subject is stored by `LongId.typeName()`.
- `docs/design/purchase-audit.md`: a note at the top that entries now store a generic subject (this document).

## Out of scope

- Demo-data actors and book titles in descriptions (PR #1).
- Two deliveries of the same event at the same time (would need a unique index).
- Deduplicating events that don't set their own `occurredAt`.
- Filtering by subject type alone.
