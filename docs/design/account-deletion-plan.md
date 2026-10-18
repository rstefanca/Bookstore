# Account ids and account deletion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Identify accounts by `AccountId` instead of username everywhere, so a reused username inherits nothing, and delete accounts completely or softly (anonymized) depending on whether any module keeps records for them.

**Architecture:** `AccountId` moves to `shared` and the signed-in account's id travels in a custom principal (`CurrentUser.id()`). Owner columns and event fields switch from username strings to the id, module by module, with data migrations. `security` gains `deleted_at`, a public `AccountDeletionPolicy` SPI that `ordering`, `rating`, `customer` and `audit` implement, and a public `Accounts` for resolving display names.

**Tech Stack:** Spring Boot 4.1 / Hibernate 7, Spring Modulith, Flyway, JUnit 5 + Testcontainers, `RestTestClient`.

**Spec:** `docs/design/account-deletion.md`

## Global Constraints

- `AccountId` is a record wrapping `long` implementing `shared.LongId`, in `cz.codingmonkey.bookstore.shared`, persisted with the existing Hibernate `UserType` pattern (`AccountIdType extends LongIdType`), serialized as a plain number.
- Tombstone username: `~deleted-<accountId>`. `~` is not allowed in usernames (pattern `[A-Za-z0-9._-]{3,100}` stays). Display name for soft-deleted or unknown accounts: `(deleted user)`.
- Username matching against accounts is case-insensitive (`lower(username)`), as the unique index is.
- Migrations: `V29__add_account_deleted_at.sql`, then one migration per owner, in this order: `V30` ratings, `V31` saved addresses, `V32` carts and orders, `V33` audit entries. Never edit an existing migration. Each data-converting migration gets a test like `AuditSubjectMigrationTest` (Flyway `target(...)` before and after, real rows).
- Data conversion rules: carts, cart lines and saved addresses whose username has no account are deleted; orders, ratings and audit entries whose username has no account get a placeholder account (role `CUSTOMER`, unusable password hash, `deleted_at` set, username `~deleted-<its id>`); each migration creates its own placeholders.
- Modules never read another module's tables. Module dependencies stay one way: `security` depends on `shared` only; `ordering`, `rating`, `customer` and `audit` depend on `security`'s public types.
- API compatibility: `customer` (orders) and `actor` (audit) stay username strings resolved when read; `customerId` and `actorId` are added.
- Soft-deleted accounts: cannot sign in, are not found by username lookups or the duplicate-username check, are left out of `GET /api/accounts`, and do not count as admins.
- Deleting keeps the existing rules (not your own account, not the last admin). `204` for the caller either way.
- On a soft-deleted customer's orders the invoice address group is kept and the shipping address group is set to NULL.
- Change logging only through `shared.ChangeLog`; no more personal data in log lines than today.
- Every Spring test imports `TestcontainersConfiguration`. Run tests with `./gradlew test --tests '<class>'`; Docker must be running. Stage files by explicit path; never `git add -A`.

## Review Focus

- Signing in with a different letter case of the username resolves to the same `AccountId` (existing `AddressIntegrationTest.usernameCaseDoesNotMatter` and the rating equivalent stay green; Tasks 1, 3, 4).
- A soft-deleted account cannot sign in with its old username or with its tombstone name (Tasks 2, 5).
- Orders, ratings and audit entries of users who were already gone before the migration survive as `(deleted user)` (Tasks 3, 4 migration tests).
- A page of orders or audit entries with many different accounts resolves names in one query, and a deleted one shows `(deleted user)` (Task 4).
- A shipping-address scrub racing an order step (`OrderProcess`) must not be undone by the step writing back its stale copy (Task 5).
- A username registered again after a hard and after a soft delete sees no cart, orders, ratings, addresses or audit trail of the old account (Task 5).
- A soft-deleted admin does not count as an admin for the last-admin rule (Tasks 2, 5).
- Registering `~deleted-5` is rejected as a username (Task 2).

---

### Task 1: `AccountId` in `shared`, principal carries it, `CurrentUser.id()`

**Files:**
- Move: `security/AccountId.java`, `security/AccountIdType.java` to `shared/` (package `cz.codingmonkey.bookstore.shared`); fix imports everywhere (`git mv`).
- Create: `shared/AccountPrincipal.java`, `security/AccountUser.java`
- Modify: `security/AccountUserDetailsService.java`, `shared/CurrentUser.java`
- Test: `src/test/java/cz/codingmonkey/bookstore/shared/CurrentUserTest.java` (new), `TypeNamesTest.java` (import fix)

**Interfaces:**
- Produces:
  - `shared.AccountId` and `AccountIdType` (unchanged shape, new package).
  - `public interface AccountPrincipal { AccountId accountId(); }` in `shared`.
  - `class AccountUser extends org.springframework.security.core.userdetails.User implements AccountPrincipal` in `security` (constructor takes the id plus what `User.withUsername(...).roles(...)` builds today).
  - `CurrentUser.id(): @Nullable AccountId` (the signed-in account, `null` for anonymous, no authentication, or a principal that isn't an `AccountPrincipal`), `CurrentUser.requireId(): AccountId` (throws `IllegalStateException` when `id()` is `null`). `CurrentUser.username()` stays, documented as for display and logging only.

- [ ] **Step 1: Write the failing `CurrentUserTest`** (plain unit test, set and clear `SecurityContextHolder`): `idIsNullWithoutAuthentication`; `idIsNullForAnonymous` (`AnonymousAuthenticationToken`); `idComesFromTheAccountPrincipal` (a `UsernamePasswordAuthenticationToken` whose principal is a stub `AccountPrincipal` with id 42 gives `new AccountId(42)`); `requireIdThrowsWithoutAUser`; `usernameStillWorks`.
- [ ] **Step 2: Run** `./gradlew test --tests '*CurrentUserTest'`. Expected: FAIL (compilation).
- [ ] **Step 3: Move `AccountId` and `AccountIdType` with `git mv`, fix every import, implement `AccountPrincipal`, `AccountUser`, change `AccountUserDetailsService.loadUserByUsername` to return an `AccountUser`, add `id()`/`requireId()`.** Keep `Account`'s mapping (`@Type(AccountIdType.class)`) working.
- [ ] **Step 4: Run** `CurrentUserTest`, `TypeNamesTest`, `SecurityIntegrationTest`, `AccountIntegrationTest`, `ModularityTests`. Expected: PASS (nothing else changed behavior).
- [ ] **Step 5: Commit** — `Move AccountId to shared; sign-in principal carries the account id`.

---

### Task 2: `security`: soft delete mechanics, `Accounts`, `AccountDeletionPolicy`

**Files:**
- Create: `src/main/resources/db/migration/V29__add_account_deleted_at.sql`, `security/AccountDeletionPolicy.java`, `security/Accounts.java`
- Modify: `security/Account.java`, `AccountRepository.java`, `AccountService.java`, `AccountUserDetailsService.java`, `AccountController.java` (only if it needs the changed service signatures)
- Test: `security/AccountDeletionIntegrationTest.java` (new), `security/AccountIntegrationTest.java` (add cases)

**Interfaces:**
- Consumes: `AccountId` from Task 1.
- Produces:
  - `public interface AccountDeletionPolicy { boolean holdsRecords(AccountId account); void removePersonalData(AccountId account); void anonymize(AccountId account); }`
  - `public interface Accounts { String DELETED_USER = "(deleted user)"; Map<AccountId, String> usernamesOf(Collection<AccountId> accounts); }` implemented by `AccountService`; every requested id is in the map, soft-deleted and unknown ones map to `DELETED_USER`.
  - `Account` gets `@Nullable Instant deletedAt`; repository lookups by username, the duplicate check, `findAll` and `findAndLockByRole` exclude rows with `deleted_at` set; `get(id)` of a soft-deleted account throws `AccountNotFoundException`.
  - `AccountService.delete(AccountId id, String currentUsername)` follows the spec flow for any role: own-account and last-admin rules, lock the row `FOR UPDATE`, `holdsRecords` over all `ObjectProvider<AccountDeletionPolicy>` beans, `removePersonalData` on all, then hard delete or `anonymize` on all + soft delete (clear first and last name, password hash = encoder hash of random bytes, `deleted_at = now`, username = `~deleted-<id>`).

- [ ] **Step 1: Write the failing tests.** `AccountDeletionIntegrationTest` (`@SpringBootTest`, `@Import(TestcontainersConfiguration.class)` plus a nested `@TestConfiguration` registering a recording fake `AccountDeletionPolicy` whose `holdsRecords` answers from a mutable set): `hardDeleteWhenNobodyHoldsRecords` (row gone, `removePersonalData` called, `anonymize` not); `softDeleteWhenSomeoneHoldsRecords` (row stays, `deleted_at` set, username `~deleted-<id>`, names null, `anonymize` and `removePersonalData` called); `softDeletedAccountCannotSignIn` (HTTP Basic with the old username and password gives 401; with the tombstone name 401); `usernameIsFreeAfterSoftDelete` (register the same username again: 201, a different id); `softDeletedAccountIsHiddenFromTheList` (`GET /api/accounts` lacks it); `softDeletedAdminDoesNotCountAsAdmin` (two admins, soft-delete one through the fake policy, deleting the other gives the last-admin refusal); `deletingYourselfIsStillRefused`; `usernamesOfMapsDeletedAndUnknownToDeletedUser`. In `AccountIntegrationTest` add `tombstoneNamesCannotBeRegistered` (`~deleted-5` gives 400).
- [ ] **Step 2: Run** the two classes. Expected: FAIL.
- [ ] **Step 3: Implement** the migration, entity field, repository changes, the two public interfaces and the `AccountService` changes. Use `ObjectProvider<AccountDeletionPolicy>` so the module works with no policies registered (then every delete is a hard delete, as today).
- [ ] **Step 4: Run** `AccountDeletionIntegrationTest`, `AccountIntegrationTest`, `SecurityIntegrationTest`, `InitialAdminIntegrationTest`, `CustomerSignUpIntegrationTest`, `ModularityTests`. Expected: PASS.
- [ ] **Step 5: Commit** — `Soft-delete accounts through an AccountDeletionPolicy hook`.

---

### Task 3: `rating` and `recommendation` by account id

**Files:**
- Create: `src/main/resources/db/migration/V30__rating_by_account_id.sql`
- Modify: `rating/Ratings.java` (`ratingsBy(AccountId)`), `rating/internal/{BookRatings,RatingService,RatingController,Rating,RatedBook}.java` (as needed), `recommendation/{PersonalPicksService,PersonalPicksController}.java`
- Test: `rating/RatingMigrationTest.java` (new, under `src/test/java/cz/codingmonkey/bookstore/rating/`), `rating/internal/RatingModuleTests.java`, `rating/RatingIntegrationTest.java`, `rating/internal/RatingRankingIntegrationTest.java`, `recommendation/*`, `storefront/RatingSortIntegrationTest.java` (adapt)

**Interfaces:**
- Consumes: `AccountId`, `CurrentUser.requireId()` (Task 1).
- Produces: `Ratings.ratingsBy(AccountId customer): Map<BookId, Integer>`; `RatingService.rate(BookId, AccountId, int)`, `find(BookId, AccountId)`; `PersonalPicksService.picksFor(AccountId, int)`; table `book_rating(book_id, customer_id BIGINT NOT NULL, stars, rated_at, PRIMARY KEY (book_id, customer_id))`.

- [ ] **Step 1: Write the failing tests.** `RatingMigrationTest` (Flyway target 29, then 30): `ratingsMoveToTheAccountId` (two accounts, one rating each, one rated with a differently cased username: after V30 each rating has the right `customer_id`); `ratingsOfGoneUsersGetAPlaceholderAccount` (a rating by a username with no account survives; its `customer_id` is a soft-deleted account named `~deleted-<id>`); `primaryKeyStillRejectsADuplicate`. Adapt `RatingModuleTests`, the integration tests and the recommendation tests to account ids: module tests use `new AccountId(<unique long>)`; HTTP tests are unchanged except any direct SQL on `book_rating.customer`.
- [ ] **Step 2: Run** `RatingMigrationTest`, `RatingModuleTests`. Expected: FAIL.
- [ ] **Step 3: Implement** `V30`, then the signature changes through `BookRatings` SQL, `RatingService`, the controller and `PersonalPicks*` (controllers take `CurrentUser.requireId()`). Add a shared test helper `src/test/java/cz/codingmonkey/bookstore/TestAccountIds.java` with `static AccountId next()` (unique per call) for module tests that have no real accounts.
- [ ] **Step 4: Run** `./gradlew test --tests 'cz.codingmonkey.bookstore.rating.*' --tests 'cz.codingmonkey.bookstore.recommendation.*' --tests 'cz.codingmonkey.bookstore.storefront.*' --tests '*ModularityTests'`. Expected: PASS.
- [ ] **Step 5: Commit** — `Ratings and personal picks by account id`.

---

### Task 4: `customer`, `ordering`, events and `audit` by account id

One task because the pieces call each other: `ordering` reads `CustomerAddresses`, every order-flow event carries the customer as its audit actor, and `Auditable.actor` has one type for all events.

**Files:**
- Create: `V31__customer_address_by_account_id.sql`, `V32__orders_by_account_id.sql`, `V33__audit_actor_by_account_id.sql`
- Modify (customer): `customer/CustomerAddresses.java`, `customer/internal/{AddressService,AddressController,SavedAddresses,SavedAddressesRepository}.java`
- Modify (events, all `AccountId`): `shared/Auditable.java` (`@Nullable AccountId actor()`), `shared/CurrentUser` callers (`CurrentUser.id()` instead of `username()`), `bookcatalog/{AuthorCreatedEvent,BookCreatedEvent,BookDeletedEvent}.java`, `pricing/PriceChangedEvent.java`, `inventory/{StockAdjustedEvent,StockReservationRequested,StockReleaseRequested}.java`, `payment/{PaymentRequested,RefundRequested}.java`, `ordering/OrderProgressedEvent.java` (`AccountId actor`, non-null), the publishers (`BookService`, `AuthorService`, `PricingService`, `InventoryService`, `CartService`, `OrderService`, `OrderProcess`, payment and inventory listeners passing the actor on)
- Modify (ordering): `ordering/internal/{Cart,CartRepository,CartService,CustomerOrder,OrderRepository,OrderService,OrderingController,OrderingResponses,OrderProcess}.java`
- Modify (audit): `audit/{AuditEntry,AuditController,AuditListener}.java` (`actor_id`)
- Test: three migration tests (`customer/AddressMigrationTest`, `ordering/OrderMigrationTest`, `audit/AuditActorMigrationTest`), plus adapting `OrderingModuleTests`, `CustomerOrderTest`, `CheckoutDeleteRaceTest`, `CustomerModuleTests`, `AuditModuleTests`, `AuditActorIntegrationTest`, `AuditControllerIntegrationTest`, every module/integration test that builds events with an actor, `ordering/CheckoutAddressesIntegrationTest`, `ordering/OrderingIntegrationTest`

**Interfaces:**
- Consumes: `AccountId`, `CurrentUser.id()/requireId()`, `Accounts.usernamesOf` (Tasks 1–2).
- Produces:
  - `CustomerAddresses.shipping(AccountId)` / `invoice(AccountId)`; `AddressService.get/saveShipping/saveInvoice/deleteShipping/deleteInvoice(AccountId, ...)`.
  - `CartService.view/setQuantity/clear/checkout(AccountId customer, ...)`, `restore(CustomerOrder)`; `OrderService.findOwn(AccountId, ...)`, `cancel(AccountId, OrderId)`; `CustomerOrder(AccountId customer, ...)`, `AccountId getCustomer()`; tables `cart(customer_id PK)`, `cart_line(customer_id, ...)`, `customer_order(customer_id NOT NULL)` with index `(customer_id, created_at DESC)`, `customer_address(customer_id PK)`, `audit_entry.actor_id BIGINT`.
  - `OrderView` gets `AccountId customerId`; its `customer` is the username from `Accounts.usernamesOf` (`OrderView.from(order, Map<AccountId, String> usernames)`; the own-order paths resolve one id, the staff list resolves its page with a single call). `AuditEntryResponse` gets `@Nullable AccountId actorId` and resolves `actor` the same way.

- [ ] **Step 1: Write the failing migration tests** (Flyway target before/after each, real rows): `AddressMigrationTest` (addresses move to the right `customer_id`, including a differently cased username; an address of a username with no account is deleted); `OrderMigrationTest` (orders, carts and cart lines convert; carts of gone users are deleted; orders of gone users get a soft-deleted placeholder account; the descending index exists); `AuditActorMigrationTest` (actors map to ids; an entry with no actor keeps `actor_id` NULL; an actor with no account gets a placeholder).
- [ ] **Step 2: Adapt the existing tests** to the new signatures (no behavior change): module and unit tests use `TestAccountIds.next()` (Task 3) for customers and actors; `AuditModuleTests` and the event-building tests pass an `AccountId` actor. Add `ordersShowTheUsernameAndTheId` (`CheckoutAddressesIntegrationTest` or `OrderingIntegrationTest`: a placed order has `customer` equal to the username and `customerId` equal to the account's id), `staffListResolvesNamesForManyCustomers` (three customers' orders in `GET /api/orders/all`: each `customer` is that customer's username; Hibernate/JDBC statement count for the resolution is one — assert with a `SELECT` counter or by checking the code calls `usernamesOf` once per page), `deletedCustomerShowsAsDeletedUser` (set `deleted_at` by JDBC on a customer with an order: `customer` is `(deleted user)`, `customerId` unchanged), `auditEntriesShowActorNameAndId` and `auditEntryOfADeletedStaffMemberShowsDeletedUser` in `AuditControllerIntegrationTest`.
- [ ] **Step 3: Run** the three migration tests and `OrderingModuleTests`. Expected: FAIL (compilation or assertions).
- [ ] **Step 4: Implement**, in this order so each part compiles on its own: (1) `V31`–`V33` and the three migration tests green; (2) `Auditable.actor`/event types and `audit` (`AuditEntry.actorId`, listener, controller response with `Accounts.usernamesOf`); (3) publishers outside ordering switch to `CurrentUser.id()`; (4) `ordering` (entities, repositories, services, controller using `CurrentUser.requireId()`, `OrderView`); (5) `customer` (`CustomerAddresses`, service, controller) and `ordering.CartService` calling it with the id. `OpenApiIntegrationTest` must still pass with the new response fields documented in Javadoc.
- [ ] **Step 5: Run** the full suite `./gradlew test`. Expected: PASS.
- [ ] **Step 6: Commit** (stage by path) — `Customers, orders, events and the audit log by account id`.

---

### Task 5: Policies and end-to-end deletion

**Files:**
- Create: `ordering/internal/OrderingAccountDeletion.java`, `rating/internal/RatingAccountDeletion.java`, `customer/internal/CustomerAccountDeletion.java`, `audit/AuditAccountDeletion.java` (or the module's internal package; each a `@Component` implementing `security.AccountDeletionPolicy`)
- Modify: `ordering/internal/{OrderRepository,CartRepository}.java`, `rating/internal/BookRatings.java`, `audit/AuditEntryRepository.java` (existence queries and the shipping scrub)
- Test: per-module policy tests (`OrderingModuleTests`, `RatingModuleTests`, `CustomerModuleTests`, `AuditModuleTests` additions), `security/AccountDeletionEndToEndTest.java` (new, all modules), `ordering/internal/ShippingScrubRaceTest.java` (new)

**Interfaces:**
- Consumes: `AccountDeletionPolicy` (Task 2), the id-based modules (Tasks 3–4).
- Produces: the four policy beans per the spec table: `ordering` holds records when the account has an order, `removePersonalData` deletes the cart, `anonymize` sets the shipping group of its orders to NULL; `rating` holds when it has a rating; `customer` deletes the saved addresses; `audit` holds when an entry has this `actor_id`. Nothing else overrides.

- [ ] **Step 1: Write the failing tests.** Module tests: `ordering` — `holdsRecordsWithAnOrder`/`doesNotWithoutOne`, `removePersonalDataDeletesTheCart`, `anonymizeScrubsShippingAndKeepsInvoice`; `rating` — `holdsRecordsWithARating`; `customer` — `removePersonalDataDeletesAddresses`; `audit` — `holdsRecordsWithAnActorEntry`. `AccountDeletionEndToEndTest` (HTTP, all modules, admin client deletes via `DELETE /api/accounts/{id}`): `customerWithOnlyCartAndAddressIsHardDeleted` (row gone; same username registers again; its cart, addresses and ratings are empty); `customerWithOrderAndRatingIsSoftDeleted` (sign-in fails; username registers again as a clean account with no orders/ratings/addresses; the old order shows `customer` `(deleted user)`, its `customerId`, `shipping` null, `invoice` present; the book's average still counts the rating; cart and saved addresses gone; absent from `GET /api/accounts`); `staffWithoutAuditEntriesIsHardDeleted`; `staffWithAuditEntriesIsSoftDeletedAndReuseStartsClean` (the same username registered again has an empty audit trail attributed to it; old entries show `(deleted user)`); `softDeletedAdminNoLongerCountsAsAdmin`; `oldCredentialsGive401AfterDeletion`. `ShippingScrubRaceTest`: scrub shipping while an `OrderProcess` step for the same order is mid-flight (use two threads and a latch around the step transaction like `CheckoutDeleteRaceTest`); afterwards the order's shipping group is still NULL.
- [ ] **Step 2: Run** the new tests. Expected: FAIL.
- [ ] **Step 3: Implement** the four beans and repository queries. The shipping scrub must not be undone by a concurrent order step that holds a stale copy: update the columns with a repository query that also bumps `version`, so a stale writer fails its version check and retries, and have the test prove it; if `OrderProcess` does not retry optimistic failures today, say so in the report instead of changing its retry behavior.
- [ ] **Step 4: Run** the new tests and then `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** — `Deleting accounts: hard or soft, with orders, ratings and audit kept`.

---

### Task 6: Docs and final build

**Files:**
- Modify: `README.md` (accounts section: ids, hard vs soft delete, the tombstone and `(deleted user)`, sign-in refused, `customerId` and `actorId` fields, the deployment note that the app must be stopped gracefully with no incomplete event publications before upgrading because stored events carried usernames; module table and dependency diagram; API table rows), `CLAUDE.md` (typed ids and `AccountId` in `shared`, `CurrentUser.id()`, audited events carry an `AccountId`, `AccountDeletionPolicy` next to `BookDeletionPolicy`), `docs/design/orders.md` and `docs/design/customer-addresses.md` (owner is the account id; scrubbed shipping address), `docs/design/account-deletion.md` (Status implemented with the date; migrations are `V29`–`V33` split by owner instead of one `V30`; each migration creates its own placeholder accounts, so the same long-gone username can have a different placeholder per module)

- [ ] **Step 1: Make the edits** in each file's existing style.
- [ ] **Step 2: Run** `./gradlew build`. Expected: BUILD SUCCESSFUL, including `ModularityTests`.
- [ ] **Step 3: Commit** — `Document account ids and account deletion`.

---

## Self-review notes

- Spec coverage: §1 identity → Task 1 (plus controllers in Tasks 3–4); §2 data and migration → Tasks 2–4 (V29–V33; deployment note in Task 6); §3 security → Task 2; §4 modules → Task 5 (plus the API fields in Task 4); §5 not locked → no task (documented in Task 6); §6 boundaries and docs → Task 6 and `ModularityTests` in every task; §7 testing → tests in every task.
- Where this plan decides something the spec left open: migrations split by owner (`V30`–`V33`) so each task leaves a working build; placeholders created per module; `CurrentUser.requireId()` for controllers; test helper `TestAccountIds`; the shipping scrub bumps `version`.
- Task 4 is large on purpose: `Auditable.actor` has one type for all events and the order-flow events carry the customer, so ordering, customer, events and audit cannot be switched separately without throwaway adapters.
