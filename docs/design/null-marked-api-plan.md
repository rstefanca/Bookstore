# Non-null Public API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every module's root package is `@NullMarked` (JSpecify), with `@Nullable` exactly where `null` is allowed.

**Architecture:** Package-level `@NullMarked` in the ten root `package-info.java` files makes their API non-null by
default; type-use `@Nullable` marks the documented or code-checked exceptions. Annotations only: no build plugin,
no behavior change.

**Tech Stack:** Java 25, JSpecify 1.0 (`org.jspecify.annotations.NullMarked` / `Nullable`, already on the classpath
through Spring Framework 7), Spring Boot 4, Spring Modulith, Gradle.

**Spec:** `docs/design/null-marked-api.md`

## Global Constraints

- Branch `null-marked-api` (from `main`). `build.gradle.kts` does not change.
- Annotations: `org.jspecify.annotations.NullMarked` on packages, `org.jspecify.annotations.Nullable` everywhere
  else; never `javax.annotation`, `jakarta.annotation`, `org.springframework.lang` or JetBrains annotations.
- `@Nullable` is type-use: directly before the type (`@Nullable String actor`, `private @Nullable String firstName`,
  `public @Nullable String username()`); for a fully qualified type, after the package (`java.math.@Nullable
  BigDecimal`).
- Rule (spec §2): `@Nullable` iff the Javadoc calls it optional or `null`, or the code checks it for `null` or passes
  `null` on purpose. Not annotated: JPA-managed state only `null` before persist / in the no-arg constructor;
  `@NotBlank`/`@NotNull` request fields; Spring Data repository methods.
- `internal` packages and test code are not touched.
- No runtime behavior change; lines ≤ 120 characters.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

Annotations change behavior in two Spring places; these are the ways this plan could break a running app:

- **Spring MVC / Spring AI parameters**: `@Nullable` on a handler or `@McpTool` parameter makes it optional
  (`MethodParameter.isOptional()`). Only parameters that already have `required = false` may get `@Nullable`;
  check: `grep -n "@Nullable" *Controller.java *McpTools.java` shows each next to `required = false`.
- **Spring Data repositories** in a `@NullMarked` package get runtime null checks: a `null` argument or a `null`
  single-entity return throws. `AuditEntryRepository` and `AccountRepository` return only `Optional`, `Page`,
  `List` and `boolean`, and every caller passes non-null arguments (checked while planning); Task 3 keeps them
  unannotated and its full-suite run is the evidence.
- **Configuration properties**: `@Nullable` on `AccountProperties.InitialAdmin` / `RecommendationProperties`
  components must not change binding or defaults (`@DefaultValue` stays).
- **Record accessors in `Auditable` events**: an event component marked `@Nullable` must also be `@Nullable` on
  the interface method it implements (`Auditable.actor()`), or the IDE reports a mismatch.
- **Fully qualified types**: `@Nullable java.math.BigDecimal` does not compile; use `java.math.@Nullable BigDecimal`
  (only if a file uses the qualified form).

---

### Task 1: `@NullMarked` packages, `shared`, and the convention

**Files:**
- Modify: `src/main/java/cz/codingmonkey/bookstore/{audit,bookcatalog,inventory,ordering,payment,pricing,
  recommendation,security,shared,storefront}/package-info.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/shared/Auditable.java`, `shared/CurrentUser.java`,
  `shared/LongIdType.java`
- Modify: `CLAUDE.md` ("Cross-cutting conventions")

**Interfaces:**
- Produces: `Auditable.@Nullable String actor()`, `CurrentUser.username()` returning `@Nullable String` (events in
  Tasks 2–3 implement `actor()` with a `@Nullable` component).

- [ ] **Step 1: Annotate the ten `package-info.java` files**

Add `@org.jspecify.annotations.NullMarked` before the `package` line, next to the existing
`@org.springframework.modulith.ApplicationModule(...)` (fully qualified like it; Javadoc unchanged).

- [ ] **Step 2: `shared` nullable spots**

- `Auditable.actor()`: `@Nullable String actor();`
- `CurrentUser.username()`: return `@Nullable String`.
- `LongIdType`: every override that takes or returns `null` (`nullSafeGet` return, `nullSafeSet` `value`,
  `disassemble`/`assemble` and the value-converter's `toDomainValue`/`toRelationalValue` parameters and returns —
  whatever methods in the file check or return `null`).
- Re-read the other `shared` files (`ChangeLog`, `LongId`, `CachingConfiguration`, `ThreadingConfiguration`) by the
  rule; expected: nothing else.

- [ ] **Step 3: CLAUDE.md bullet**, under "Cross-cutting conventions", verbatim from spec §3:

```markdown
- **Null-safety**: module root packages are `@NullMarked` (JSpecify), so their API is non-null unless marked
  `@Nullable`; `internal` packages are unmarked. Annotations only: the build does not check them.
```

- [ ] **Step 4: Verify**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL. `grep -L "NullMarked" src/main/java/cz/codingmonkey/bookstore/*/package-info.java`
prints nothing.

- [ ] **Step 5: Commit** — `Null-mark module root packages; nullable spots in shared`.

---

### Task 2: Events and data types (bookcatalog, inventory, payment, pricing, ordering)

**Files:** every `.java` file directly in `src/main/java/cz/codingmonkey/bookstore/{bookcatalog,inventory,payment,
pricing,ordering}/` (not `internal`).

**Interfaces:**
- Consumes: `Auditable.@Nullable String actor()` (Task 1).

- [ ] **Step 1: Annotate, per spec §2 table**

- bookcatalog: `actor` of `AuthorCreatedEvent`, `BookCreatedEvent`, `BookDeletedEvent`; `description` of
  `BookCreatedEvent` and `BookUpdatedEvent`; every component of `BookSearch` (`title`, `authorName`, `authorId`,
  `genreId`, `condition`) and the 4-argument constructor's parameters; `BookDetails.publishedYear`,
  `BookDetails.description`.
- inventory: `actor` of `StockAdjustedEvent`, `StockReleaseRequested`, `StockReservationRequested`.
- payment: `actor` of `PaymentRequested`, `RefundRequested`.
- pricing: `PriceChangedEvent.oldPrice`, `newPrice`, `actor`.
- ordering: nothing (`OrderProgressedEvent.actor` is non-null).
- Re-read every other file in these five packages by the rule (`Money`, `Prices`, `BookCatalog`, `BookCondition`,
  `BookDeletionPolicy`, ids and id types, the remaining events); annotate anything else the rule hits and list it
  in the report.

- [ ] **Step 2: Verify**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit** — `Nullable spots in catalog, inventory, payment and pricing APIs`.

---

### Task 3: Root packages with implementation classes (storefront, recommendation, security, audit)

**Files:** every `.java` file directly in `src/main/java/cz/codingmonkey/bookstore/{storefront,recommendation,
security,audit}/`.

**Interfaces:**
- Consumes: `Auditable.@Nullable String actor()`, `CurrentUser.username()` → `@Nullable String` (Task 1);
  `BookDetails.publishedYear`/`description` `@Nullable` (Task 2).

- [ ] **Step 1: Annotate, per spec §2 table**

- storefront: `StorefrontBook.price`, `publishedYear`, `description`; `PriceRange.min`, `max`;
  `StorefrontController.list` parameters with `required = false`; `StorefrontMcpTools` parameters with
  `required = false`; `blankToNull` (parameter and return) and `checkLimit` (parameter).
- recommendation: `RecommendationMcpTools` `limit` (`required = false`); the description parameter of
  `BookEmbeddingUpdater.update`; `RecommendationProperties` components only if the code checks them for `null`.
- security: `Account.firstName`, `lastName` (fields, getters, constructor/setter parameters);
  `AccountResponse.firstName`, `lastName`; `AccountProperties.InitialAdmin.password`, `firstName`, `lastName`;
  `AccountService` name parameters documented as optional and its strip helper (parameter and return).
- audit: `AuditEntry.actor` (field, getter, `of` parameter); `AuditController.AuditEntryResponse.actor`;
  `AuditController.list` `subjectType`, `subjectId`.
- Re-read every other file in these four packages by the rule; do NOT annotate `AuditEntryRepository` /
  `AccountRepository` methods. List anything extra in the report.

- [ ] **Step 2: Check the Review Focus items**

`grep -n "@Nullable" src/main/java/cz/codingmonkey/bookstore/*/*Controller.java
src/main/java/cz/codingmonkey/bookstore/*/*McpTools.java` — every hit is on a parameter that also has
`required = false`.

- [ ] **Step 3: Verify with the full suite** (Spring MVC, MCP, Spring Data and config binding run here)

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 0 test failures (report totals from `build/test-results/test/`).

- [ ] **Step 4: Commit** — `Nullable spots in storefront, recommendation, security and audit`; then set the
  spec's status line to `Status: implemented (<date>).` in the same commit.
