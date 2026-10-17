# Generic Audit Subject Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Audit entries store their subject as any `LongId` (type name + value), and the audit module depends only
on `shared`.

**Architecture:** `LongId.typeName()` derives a stable type name from the id class. The audit module keeps the
subject in an embeddable `AuditSubject(typeName, value)` that itself implements `LongId`, stores the action as a
plain string, skips a redelivered event by (subject, action, occurredAt), and exposes generic
`subjectType`/`subjectId` filters. Migration V23 moves existing rows from `book_id`/`author_id`/`order_id`.

**Tech Stack:** Java 25, Spring Boot 4, Spring Modulith (`@ApplicationModuleListener`, `Scenario`,
`allowedDependencies`), JPA/Hibernate 7 (record embeddables), Flyway, Testcontainers PostgreSQL (pgvector), Gradle.

**Spec:** `docs/design/audit-subject.md`

## Global Constraints

- Branch `audit-long-id-subject` (from `main`); PR #1 (`audit-subject`) is not touched.
- Migration file: `src/main/resources/db/migration/V23__audit_entry_subject.sql` (latest today is V22). Existing
  migrations are not edited.
- `src/main/java/cz/codingmonkey/bookstore/audit` imports nothing from `cz.codingmonkey.bookstore.*` except
  `shared`; its tests import no other module either (except `TestcontainersConfiguration`).
- Action format: `[A-Z][A-Z0-9_]*`, at most 50 characters. Subject type format in the API: `[A-Z][A-Z0-9_]*`.
- Log line exactly `Recorded audit entry {action} for {subject}` with `{subject}` = lower-case type name, space,
  value (`for book 2`, `for order 52`); never actor or description.
- API error texts exactly: `Filter by both subjectType and subjectId`, `Use subjectType and subjectId`.
- Lines ≤ 120 characters. Data changes logged via `shared.ChangeLog`. Tests need Docker.
- Test command: `./gradlew test --tests '<pattern>'`. Commit messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**Deviation from the spec (decided while planning):** `AuditEntryId` and `PricePeriodId` are package-private, so
`TypeNamesTest` in the root package pins the seven public ids. Only public ids can appear in events that reach the
audit module, so these are the names that get stored. Task 3 updates the spec's test section accordingly.

## Review Focus

- An `AuditSubject` passed back into `AuditEntry.of` / `AuditSubject.of` (e.g. from `getSubject()`) must keep its
  stored type, not become `AUDIT_SUBJECT` → test in Task 2 (`subjectKeepsItsTypeWhenWrappedAgain`).
- Action length boundary: exactly 50 characters is accepted, 51 rejected → Task 2 (`actionFormat`).
- An event whose `auditSubject()` is `null` fails with a clear message rather than an NPE deep in Hibernate →
  Task 2 (`actionFormat` also asserts `NullPointerException` with message `subject`).
- Old filter mixed with new ones or given empty (`?subjectType=BOOK&subjectId=1&bookId=1`, `?orderId=`) is still
  400, never the whole log → Task 2 (`oldFiltersAreRejected`).
- Non-numeric `subjectId` (`?subjectType=BOOK&subjectId=abc`) is 400, not 500 → Task 2 (`invalidFilters`).

---

### Task 1: `LongId.typeName()`

**Files:**
- Modify: `src/main/java/cz/codingmonkey/bookstore/shared/LongId.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/shared/Auditable.java` (Javadoc only)
- Test: `src/test/java/cz/codingmonkey/bookstore/TypeNamesTest.java` (new, plain JUnit, no Spring)

**Interfaces:**
- Produces: `default String typeName()` on `LongId`.

- [ ] **Step 1: Write the failing test**

```java
/** typeName() is stored in the audit log: renaming an id class changes stored audit data. */
class TypeNamesTest {

    @Test
    void typeNamesAreDerivedFromTheClassName() {
        assertThat(new BookId(1).typeName()).isEqualTo("BOOK");
        assertThat(new AuthorId(1).typeName()).isEqualTo("AUTHOR");
        assertThat(new OrderId(1).typeName()).isEqualTo("ORDER");
        assertThat(new GenreId(1).typeName()).isEqualTo("GENRE");
        assertThat(new AccountId(1).typeName()).isEqualTo("ACCOUNT");
        assertThat(new ReservationId(1).typeName()).isEqualTo("RESERVATION");
        assertThat(new PaymentReference(1).typeName()).as("no Id suffix, two words").isEqualTo("PAYMENT_REFERENCE");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew test --tests cz.codingmonkey.bookstore.TypeNamesTest`
Expected: compilation failure, `cannot find symbol: method typeName()`.

- [ ] **Step 3: Implement `typeName()` in `LongId`**

Javadoc and body exactly as in spec section 1. Update `Auditable`'s Javadoc for `auditAction()`, `auditSubject()`
and `occurredAt()` as spec section 1 says (also fix "a book, author or order id" → "any `LongId`").

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests cz.codingmonkey.bookstore.TypeNamesTest`
Expected: PASS.

- [ ] **Step 5: Commit** — `Add LongId.typeName()`.

---

### Task 2: Audit module stores any `LongId` subject

One task because the migration, entity, repository, listener and controller only compile and pass
`ddl-auto: validate` together.

**Files:**
- Create: `src/main/resources/db/migration/V23__audit_entry_subject.sql` (exactly spec section 2)
- Create: `src/main/java/cz/codingmonkey/bookstore/audit/AuditSubject.java` (exactly spec section 3)
- Modify: `audit/AuditEntry.java`, `audit/AuditEntryRepository.java`, `audit/AuditListener.java`,
  `audit/AuditController.java`, `audit/package-info.java`
- Test (new): `src/test/java/cz/codingmonkey/bookstore/audit/AuditSubjectMigrationTest.java`,
  `src/test/java/cz/codingmonkey/bookstore/audit/AuditControllerIntegrationTest.java`
- Test (rewrite): `src/test/java/cz/codingmonkey/bookstore/audit/AuditModuleTests.java`

**Interfaces:**
- Consumes: `LongId.typeName()` (Task 1).
- Produces:
  - `record AuditSubject(String typeName, long value) implements LongId` with `static AuditSubject of(LongId)`,
    `toString()` = `"book 2"`.
  - `AuditEntry.of(String action, LongId subject, String actor, String description, Instant occurredAt)`;
    getters `getAction(): String`, `getSubject(): AuditSubject`, `getActor()`, `getDescription()`,
    `getOccurredAt()`, `getId()`.
  - Repository: `findAllBy(Pageable)`, `findBySubject(AuditSubject, Pageable)`,
    `findBySubjectOrderByOccurredAtDesc(AuditSubject)`,
    `existsBySubjectAndActionAndOccurredAt(AuditSubject, String, Instant)`.
  - `GET /api/audit?subjectType=&subjectId=`, entries `{id, action, subject: {type, id}, actor, description,
    occurredAt}` (Task 3's tests use this).

- [ ] **Step 1: Write the migration test** (`AuditSubjectMigrationTest`, structured like `AuditOrderMigrationTest`)

`existingEntriesKeepTheirSubject`: migrate to target `"22"`; insert with raw SQL a `BOOK_CREATED` row with
`book_id = 7`, an `AUTHOR_CREATED` row with `author_id = 8`, an `ORDER_PLACED` row with `order_id = 9`; migrate to
`"23"`; assert
`SELECT subject_type || ':' || subject_id FROM audit_entry ORDER BY id` equals `["BOOK:7", "AUTHOR:8", "ORDER:9"]`
and the latest `flyway_schema_history` version is `"23"`.

- [ ] **Step 2: Rewrite `AuditModuleTests`** (same class annotations; remove every domain import)

Test-only types, nested in the test class:

```java
record OrderId(long value) implements LongId { }   // typeName() "ORDER", like the real one

record Audited(String auditAction, LongId auditSubject, String auditDescription, String actor,
        Instant occurredAt) implements Auditable { }
```

Tests (each publishes via `scenario.publish(...)` and waits with
`andWaitForStateChange(() -> repository.findBySubjectOrderByOccurredAtDesc(subject), list -> !list.isEmpty())`,
where `subject = new AuditSubject("ORDER", n)`; use a distinct id per test):

- `recordsAnySubject`: publish `Audited("ORDER_PAYMENT_DECLINED", new OrderId(48), "Declined", "rita",
  Instant.parse("2026-10-16T08:00:00.123456Z"))`; single entry with action `ORDER_PAYMENT_DECLINED`, subject
  `new AuditSubject("ORDER", 48)`, actor `rita`, description `Declined`, `occurredAt` equal to the published one.
- `recordsChangesWithoutActor`: actor `null` → entry's actor is `null`.
- `theSameEventDeliveredAgainIsRecordedOnce`: publish the same `Audited` (order 50, `occurredAt`
  `2026-10-16T08:00:00.123456789Z`) twice (second publish `andWaitForStateChange(() -> true)`); then
  `await().during(700 ms).atMost(2 s)` asserts exactly one entry, whose `occurredAt` is
  `2026-10-16T08:00:00.123456Z`.
- `theSameActionAtAnotherTimeIsRecordedAgain`: two `Audited` for order 53, same action, `occurredAt` one second
  apart → two entries.
- `logsOnlyActionAndSubject` (`@ExtendWith(OutputCaptureExtension.class)`): publish
  `Audited("ORDER_STOCK_MISSING", new OrderId(52), "Not enough stock: Sapiens", "rita@example.com", Instant.now())`;
  output contains `Recorded audit entry ORDER_STOCK_MISSING for order 52` and not `rita@example.com` or `Sapiens`.
- `actionFormat` (no scenario): `AuditEntry.of(action, new OrderId(1), null, "x", Instant.now())` throws
  `IllegalArgumentException` for `"book_created"`, `"1BOOK"`, `"BOOK-CREATED"`, `""` and `"A".repeat(51)`; accepts
  `"A".repeat(50)`; with subject `null` throws `NullPointerException` with message `subject`.
- `subjectKeepsItsTypeWhenWrappedAgain` (no scenario): `AuditSubject.of(new AuditSubject("BOOK", 2))` equals
  `new AuditSubject("BOOK", 2)`; `new AuditSubject("BOOK", 2).toString()` is `"book 2"`.

Delete `databaseRequiresExactlyOneSubject`, `databaseRecordsEachOrderStepOnce` and the domain-event tests
(`recordsBookCreated`, `recordsPriceChanges`, ...): descriptions are the publishing modules' concern and are covered
by their HTTP tests.

- [ ] **Step 3: Write `AuditControllerIntegrationTest`**

`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@AutoConfigureRestTestClient`,
`@Import(TestcontainersConfiguration.class)`, autowired `RestTestClient admin` and `AuditEntryRepository`. Seed
entries with `repository.save(AuditEntry.of(...))` using `new AuditSubject("BOOK", n)` /
`new AuditSubject("AUTHOR", n)` (unique `n` per test, e.g. from `System.nanoTime() % 1_000_000 + 1_000_000`).

- `filtersBySubject`: two entries for book `n`, one for author `n`; `?subjectType=BOOK&subjectId={n}` returns 2
  entries, every `$.content[*].subject.type` is `BOOK` and every `$.content[*].subject.id` is `n`; the response
  has no `bookId`, `authorId` or `orderId` fields (`jsonPath("$.content[0].bookId").doesNotExist()`).
- `unknownTypeGivesAnEmptyPage`: `?subjectType=SHIPMENT&subjectId=1` → 200, `$.content` empty.
- `invalidFilters` → 400 each: `?subjectType=BOOK`, `?subjectId=1`, `?subjectType=book&subjectId=1`,
  `?subjectType=B-1&subjectId=1`, `?subjectType=BOOK&subjectId=0`, `?subjectType=BOOK&subjectId=abc`.
- `oldFiltersAreRejected` → 400 each: `?bookId=1`, `?authorId=1`, `?orderId=`,
  `?subjectType=BOOK&subjectId=1&bookId=1`.

- [ ] **Step 4: Run the audit tests to see them fail**

Run: `./gradlew test --tests 'cz.codingmonkey.bookstore.audit.*'`
Expected: compilation failure (`AuditSubject` missing, `AuditEntry.of(String, ...)` missing).

- [ ] **Step 5: Implement**

- V23 and `AuditSubject` exactly as in the spec.
- `package-info.java`: `@ApplicationModule(displayName = "Audit", allowedDependencies = "shared")`.
- `AuditEntry` per spec section 3: remove `Action`, the three id fields and their getters; `String action`
  (`@Column(nullable = false, length = 50)`), `@Embedded AuditSubject subject`. `of(...)`: 
  `Objects.requireNonNull(subject, "subject")`, action checked against a `Pattern` constant
  `[A-Z][A-Z0-9_]*` and length ≤ 50, `occurredAt.truncatedTo(ChronoUnit.MICROS)`. Class Javadoc: "Records one
  change to one subject, any `LongId`."
- `AuditEntryRepository`: the four methods from Interfaces, nothing else.
- `AuditListener`: as in spec section 3 (no `record` helper method, no imports outside `shared`).
- `AuditController`:
  - `list(@RequestParam(required = false) String subjectType, @RequestParam(required = false) Long subjectId,
    @RequestParam MultiValueMap<String, String> parameters, Pageable pageable)`.
  - Order of checks: any of `bookId`, `authorId`, `orderId` in `parameters` → 400 `Use subjectType and subjectId`;
    exactly one of `subjectType`/`subjectId` → 400 `Filter by both subjectType and subjectId`; `subjectType` not
    matching the format or `subjectId <= 0` → 400 (`ResponseStatusException`; there is no `@ControllerAdvice`).
  - Both given: `repository.findBySubject(new AuditSubject(subjectType, subjectId), page)`; otherwise
    `findAllBy`. Paging/sort as today.
  - `AuditEntryResponse(AuditEntryId id, String action, SubjectResponse subject, String actor, String description,
    Instant occurredAt)`, nested `record SubjectResponse(String type, long id)` built from `getSubject()`.

- [ ] **Step 6: Run the audit tests and the module check**

Run: `./gradlew test --tests 'cz.codingmonkey.bookstore.audit.*' --tests cz.codingmonkey.bookstore.ModularityTests`
Expected: PASS. Then `grep -rn "import cz.codingmonkey.bookstore\." src/main/java/cz/codingmonkey/bookstore/audit
src/test/java/cz/codingmonkey/bookstore/audit | grep -v "\.shared\.\|TestcontainersConfiguration"` prints nothing.

- [ ] **Step 7: Commit** — `Audit: store any LongId as subject, depend only on shared`.

---

### Task 3: Callers of `/api/audit` and docs

**Files:**
- Modify tests: `audit/AuditActorIntegrationTest.java:69,78`,
  `bookcatalog/internal/BookControllerIntegrationTest.java:143`,
  `bookcatalog/internal/AuthorControllerIntegrationTest.java:173,186`,
  `inventory/internal/InventoryControllerIntegrationTest.java:46`,
  `pricing/internal/PricingControllerIntegrationTest.java:113`, `ordering/OrderingIntegrationTest.java:133,238`
  (all under `src/test/java/cz/codingmonkey/bookstore/`)
- Modify docs: `README.md` (lines ~63, ~372 and the audit section), `CLAUDE.md` (Events section, the `Auditable`
  paragraph), `docs/design/purchase-audit.md` (note at the top), `docs/design/audit-subject.md` (status line and
  the `TypeNamesTest` bullet: seven public ids)

**Interfaces:**
- Consumes: the API from Task 2.

- [ ] **Step 1: Run the full build to see the old filters fail**

Run: `./gradlew test`
Expected: FAIL in exactly the six test classes listed above (400 instead of 200 for `?bookId=` etc.).

- [ ] **Step 2: Switch the filters**

`?bookId={id}` → `?subjectType=BOOK&subjectId={id}`, `?authorId={id}` → `?subjectType=AUTHOR&subjectId={id}`,
`?orderId={id}` → `?subjectType=ORDER&subjectId={id}`; keep other parameters (`size`). Remove the "two filters at
once" assertions (`AuthorControllerIntegrationTest:186`, `OrderingIntegrationTest:133`) and any test method left
empty by that. Any assertion on `$.content[*].bookId`/`orderId` becomes `subject.id`.

- [ ] **Step 3: Update the docs**

- README: `/api/audit` row → `?subjectType=...&subjectId=...` (e.g. `BOOK`, `AUTHOR`, `ORDER`), entries carry
  `subject: {type, id}`; purchase paragraph → `GET /api/audit?subjectType=ORDER&subjectId=N`, every audit log line
  names only action and subject; audit section: subject = `LongId.typeName()` + value, action stored as given,
  redelivered event skipped by (subject, action, occurredAt).
- CLAUDE.md: replace "and a new action also needs a value in `AuditEntry.Action`" with "the action must be upper
  snake case (≤ 50 characters) and the subject is stored as `LongId.typeName()` + value, so audit depends only on
  `shared` and needs no change for new actions or subjects".
- `purchase-audit.md`: first line after the title: `> Note: audit entries now store a generic subject
  (subject_type + subject_id); see audit-subject.md.`
- `audit-subject.md`: status → `implemented (<date>)`; `TypeNamesTest` bullet → "pins `typeName()` of the seven
  public id types (`AuditEntryId` and `PricePeriodId` are package-private and never reach the audit module)".

- [ ] **Step 4: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: Commit** — `Use generic audit filters in tests and docs`.
