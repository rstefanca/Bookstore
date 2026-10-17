# Design: non-null public API

Status: implemented (2026-10-17).

## Goal

The public Java API of every module is non-null by default: a parameter, return value, record component or field
in a module's root package may be `null` only where it says so with `@Nullable`. Today nothing is annotated and
nullability lives only in Javadoc ("{@code null} without a signed-in user").

Agreed with the user:
- **JSpecify annotations only.** No Error Prone/NullAway or other build-time check; IDEs and readers use the
  annotations.
- **Scope: each module's root package** (in Spring Modulith the module's API). `internal` packages stay unmarked.
  Root packages that also hold implementation classes (audit, recommendation, security, storefront, shared) are
  covered as a whole.
- No change in runtime behavior or JSON.

## 1. Default: `@NullMarked`

`package-info.java` of each of the ten root packages gets `@org.jspecify.annotations.NullMarked`:
`audit`, `bookcatalog`, `inventory`, `ordering`, `payment`, `pricing`, `recommendation`, `security`, `shared`,
`storefront`. The existing `@ApplicationModule` annotation and Javadoc stay.

JSpecify (`org.jspecify:jspecify` 1.0) is already on the compile classpath through Spring Framework 7, so
`build.gradle.kts` does not change.

## 2. `@Nullable`

`org.jspecify.annotations.Nullable` is a type-use annotation: it goes directly before the type
(`@Nullable String actor`). On a record component it covers the
accessor and the canonical constructor parameter.

Rule: something gets `@Nullable` if its Javadoc calls it optional or `null`, or the code checks it for `null` or
passes `null` on purpose. Everything else in a marked package is non-null.

Places (from reading the root packages; the implementation re-checks every file in them by the same rule):

| Module | Element |
|---|---|
| shared | `Auditable.actor()`; `CurrentUser.username()` return; `LongIdType` Hibernate overrides that take or return `null` (`nullSafeGet` return, `nullSafeSet` value, `disassemble`/`assemble`, the value-converter methods) |
| bookcatalog | `actor` of `AuthorCreatedEvent`, `BookCreatedEvent`, `BookDeletedEvent`; `description` of `BookCreatedEvent` and `BookUpdatedEvent` (neither has a `publishedYear`); every component of `BookSearch`; `BookDetails.publishedYear`, `BookDetails.description` |
| inventory | `actor` of `StockAdjustedEvent`, `StockReleaseRequested`, `StockReservationRequested` |
| payment | `actor` of `PaymentRequested`, `RefundRequested` |
| pricing | `PriceChangedEvent.oldPrice`, `newPrice`, `actor` |
| ordering | none: `OrderProgressedEvent.actor` is always the order's customer |
| storefront | `StorefrontBook.price`, `publishedYear`, `description`; `PriceRange.min`, `max`; optional `@RequestParam`s of `StorefrontController.list`; optional `@McpToolParam`s of `StorefrontMcpTools`; its helpers `blankToNull` (parameter and return) and `checkLimit` (parameter) |
| recommendation | optional `limit` of `RecommendationMcpTools`; the description parameter of `BookEmbeddingUpdater.update` |
| security | `Account.firstName`, `lastName` (fields, getters, constructor/setter parameters); `AccountResponse.firstName`, `lastName`; `AccountProperties.InitialAdmin.password`, `firstName`, `lastName`; `AccountService` name parameters documented as optional and its strip helper |
| audit | `AuditEntry.actor` (field, getter, `of` parameter); `AuditController.AuditEntryResponse.actor`; `subjectType`, `subjectId` of `AuditController.list` |

Not annotated:
- JPA-managed state that is only `null` before persist or inside the no-arg constructor (generated ids, fields
  Hibernate fills); it is never read in that state.
- Request records whose fields are `@NotBlank`/`@NotNull` (validated before use).
- Spring Data repository methods (`Optional` returns already express absence).

## 3. Docs

CLAUDE.md, "Cross-cutting conventions", new bullet:

> **Null-safety**: module root packages are `@NullMarked` (JSpecify), so their API is non-null unless marked
> `@Nullable`; `internal` packages are unmarked. Annotations only: the build does not check them.

README: no change (it documents the HTTP API, whose behavior does not change).

## 4. Verification

- `./gradlew build` stays green (no behavior change; annotations only).
- One review pass over every file in the ten root packages against the rule in section 2.

## Out of scope

- Build-time enforcement (Error Prone + NullAway).
- `internal` packages and test code.
- Replacing nullable values with `Optional` or other API changes.
