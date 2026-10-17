# Design: generic audit subject

Status: approved design (2026-10-17), not implemented yet.

## Goal

Every audit entry is about exactly one thing, its subject: today a book, an author or an order, each in its own
column (`book_id`, `author_id`, `order_id`). More kinds of subjects are expected soon (for example genres or
accounts), and each would need another column, a wider check constraint and new API fields. Instead, store the
subject once as a type plus an id, so that a new kind of subject needs no schema or API change.

Agreed with the user:
- Approach **A**: two columns, `subject_type` + `subject_id`. Rejected: one `subject_id` with the type derived
  from `action` (every lookup needs a list of actions per type, which silently misses new actions), and a
  `subject JSONB` column (awkward to index and query, nothing needs the flexibility).
- `/api/audit` changes in a **breaking** way to a generic `subject` and `subjectType`/`subjectId` filters.
- **Restructure only**: the existing book, author and order entries move to the new shape; no new subject types
  are audited in this change.

Unchanged: the events other modules publish, the descriptions and actors of entries, who may read the audit log,
and the rule that each step of an order is recorded once.

## 1. Schema: `V23__audit_entry_subject.sql`

```sql
-- Audit entries are about one subject of any type, so a new kind of subject needs no new column.
-- The type names are the Java enum's (AuditSubject.Type); deliberately not restricted here.
ALTER TABLE audit_entry ADD COLUMN subject_type VARCHAR(30);
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

-- "Everything about book 2": every subject lookup (none of the old book/author columns had an index).
CREATE INDEX audit_entry_subject_idx ON audit_entry (subject_type, subject_id);
-- Each step of an order happens once, so it is recorded once, even if its event is delivered again.
CREATE UNIQUE INDEX audit_entry_order_step_once ON audit_entry (subject_id, action)
    WHERE subject_type = 'ORDER';
```

- The current `audit_entry_one_subject` check guarantees exactly one of the three ids per row, so every row gets a
  subject and `SET NOT NULL` cannot fail on existing data.
- Flyway runs a Postgres migration in one transaction: a failure leaves the table as it was.
- There is deliberately no check constraint on `subject_type` values: widening it would need a migration for
  every new type, which is what this change avoids. Only the audit module writes the table, through the enum.
- One-way: application versions before this change can't read the table afterwards (single deployment,
  `ddl-auto: validate`).
- Existing migrations are not edited.

## 2. Java model (audit module only)

### `AuditSubject` (new, package-private, `cz.codingmonkey.bookstore.audit`)

```java
/** What an audit entry is about. A new kind of subject is a new Type and factory; no migration needed. */
@Embeddable
record AuditSubject(
        @Enumerated(EnumType.STRING) @Column(name = "subject_type", nullable = false, length = 30) Type type,
        @Column(name = "subject_id", nullable = false) long id) {

    enum Type { BOOK, AUTHOR, ORDER }

    static AuditSubject of(BookId book)     { return new AuditSubject(Type.BOOK, book.value()); }
    static AuditSubject of(AuthorId author) { return new AuditSubject(Type.AUTHOR, author.value()); }
    static AuditSubject of(OrderId order)   { return new AuditSubject(Type.ORDER, order.value()); }

    /** "book 2", "order 52": as in the change log. */
    @Override
    public String toString() { return type.name().toLowerCase(Locale.ROOT) + " " + id; }
}
```

- A record embeddable (supported by Hibernate 7.4, which the project uses). The typed ids are converted in the
  factories, so no new Hibernate `UserType` is needed.
- No check that an action fits its subject type (for example `PRICE_CHANGED` only for books): the listener is
  the only writer and sets both together.

### `AuditEntry`

- `bookId`, `authorId`, `orderId` (and their getters) are replaced by `@Embedded AuditSubject subject` with
  `getSubject()`.
- Factories `forBook`, `forAuthor`, `forOrder` become:
  - `static AuditEntry about(AuditSubject subject, Action action, String actor, String description)`, with
    `occurredAt` = now;
  - `static AuditEntry about(AuditSubject subject, Action action, String actor, String description,
    Instant occurredAt)` for order steps, which bring their own time.

### `AuditEntryRepository`

The six per-column methods become:

- `Page<AuditEntry> findBySubject(AuditSubject subject, Pageable page)`
- `List<AuditEntry> findBySubjectOrderByOccurredAtDesc(AuditSubject subject)` (used by tests)
- `boolean existsBySubjectAndAction(AuditSubject subject, Action action)` (order step already recorded?)

`findAllBy(Pageable)` stays.

### `AuditListener`

- Every handler records `AuditEntry.about(AuditSubject.of(event.bookId()), ...)` (or `authorId()`,
  `orderId()`), with the same descriptions and actors as now.
- The order-step guard uses `existsBySubjectAndAction(AuditSubject.of(event.orderId()), action)`.
- Change log: `Recorded audit entry {action} for {subject} by {actor}: {description}`, where the subject prints
  as before (`for book 2`, `for author 3`). Order entries still log only `Recorded audit entry {action} for
  order {id}`: their descriptions contain the customer's username and book titles.

## 3. HTTP API: `GET /api/audit` (admin only, as before)

### Request

- No parameters: all entries.
- `subjectType` + `subjectId`: the entries about one subject, e.g. `?subjectType=BOOK&subjectId=2`.
  - `subjectType` binds to `AuditSubject.Type` (`BOOK`, `AUTHOR`, `ORDER`, upper case); anything else is 400.
  - Only one of the two given: 400, "Filter by both subjectType and subjectId".
  - `subjectId` must be positive (as typed ids elsewhere): otherwise 400.
- The old parameters `bookId`, `authorId`, `orderId` are rejected with 400, "Use subjectType and subjectId".
  Spring ignores unknown parameters, so without this a client still sending `?bookId=2` would silently get the
  whole log.
- Paging as before: newest first (`occurredAt` desc, then `id` desc), `sort` ignored, size default 20, max 100.

### Response entry

```json
{ "id": 90,
  "action": "PRICE_CHANGED",
  "subject": { "type": "BOOK", "id": 2 },
  "actor": "demo-data",
  "description": "Price of \"Dune\" (book 2) set to 12.99 EUR",
  "occurredAt": "2026-10-17T07:01:43.925731Z" }
```

- `AuditEntryResponse(AuditEntryId id, String action, SubjectResponse subject, String actor, String description,
  Instant occurredAt)`, with `record SubjectResponse(String type, long id)` in `AuditController`: the API does
  not serialize the JPA embeddable, so persistence can change without changing the API.
- The fields `bookId`, `authorId`, `orderId` are gone.

Unchanged: `SecurityConfiguration` (same path and role). The audit module has no MCP tools.

## 4. Tests

New:
- `AuditSubjectMigrationTest` (like `AuditOrderMigrationTest`): migrate to V22, insert one book, one author and one
  order entry with raw SQL, migrate to V23; each row has the expected `subject_type`/`subject_id`; a second entry
  for the same order and action is rejected.
- `AuditControllerIntegrationTest`:
  - `?subjectType=BOOK&subjectId=N` returns only that book's entries, each with `subject: {type: BOOK, id: N}`;
  - only `subjectType` or only `subjectId`: 400;
  - unknown `subjectType`: 400;
  - `subjectId=0`: 400;
  - `?bookId=1` (an old parameter): 400, not the whole log.

Changed:
- `AuditModuleTests`: repository calls become `findBySubjectOrderByOccurredAtDesc(AuditSubject.of(...))` and
  `findBySubject`; the raw-SQL database guards are rewritten for the new columns: an entry without a subject is
  rejected (replaces `databaseRequiresExactlyOneSubject`), and a second entry for the same order step is still
  rejected.
- HTTP tests filtering the audit log switch to `?subjectType=...&subjectId=...`: `AuditActorIntegrationTest`,
  `BookControllerIntegrationTest`, `AuthorControllerIntegrationTest`, `InventoryControllerIntegrationTest`,
  `PricingControllerIntegrationTest`, `DemoCatalogDataIntegrationTest`, `OrderingIntegrationTest`.
- The "two filters at once: 400" checks (`AuthorControllerIntegrationTest`, `OrderingIntegrationTest`) are
  removed; `AuditControllerIntegrationTest` covers the filter rules.

Unaffected: `LoggingIntegrationTest` (log text stays `for book N` / `for author N`), `ModularityTests` (only the
audit module changes, and its dependencies stay the same).

## 5. Docs

- README: the `/api/audit` row of the API table, the `GET /api/audit?orderId=N` mention in the purchase audit
  paragraph, and the audit section describe subjects and the new filters.
- `docs/design/purchase-audit.md`: a note at the top that audit entries now store a generic subject (this
  document); the rest stays as the record of that design.

## Out of scope

- Auditing new kinds of subjects (genres, accounts, ...): each is a later change of a new `Type` value, a factory
  and its listener.
- Filtering by subject type alone (e.g. all order entries).
