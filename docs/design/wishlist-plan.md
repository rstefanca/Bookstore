# Wishlist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Customers keep a plain wishlist of books: add, remove, and list with price and rating.

**Architecture:** A new `wishlist` Modulith module stores `(account, book, addedAt)` rows through a `JdbcClient` repository, the way `rating` does, and handles `PUT`/`DELETE`. `storefront` reads the entries from the module's public `Wishlist` API and composes the list view (catalog + prices + ratings) for `GET`.

**Tech Stack:** Spring Boot 4, Spring Modulith, `JdbcClient`, Flyway, JUnit 5 + Testcontainers, `RestTestClient`.

**Spec:** [wishlist.md](wishlist.md). It says "`WishlistItem` entity"; this plan uses a `JdbcClient` repository `WishlistItems` and no entity, like `rating.internal.BookRatings`. Update the spec's module section to match in Task 4.

## Global Constraints

- Package root `cz.codingmonkey.bookstore`; the new module is `wishlist`, internals in `wishlist.internal`. Both `package-info.java` carry `@NullMarked`; the root one also `@ApplicationModule(displayName = "Wishlist")`.
- Customers only: `hasRole(CUSTOMER)` for `GET`/`PUT`/`DELETE` on `/api/me/wishlist` and `/api/me/wishlist/*`. Admin and staff get 403.
- Limit: 200 books per wishlist. Page size default 20, max 100; `sort` ignored; order `added_at DESC, book_id`.
- Migration `V35__create_wishlist_item.sql`; never edit an existing migration.
- Every change is logged via `shared.ChangeLog` (account id and book id only). No `Auditable` event.
- New endpoints need `@Tag`, Javadoc summary and params, `@ApiResponse` per status including 204, `@ResponseStatus(NO_CONTENT)`.
- Commits end with: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Work on a feature branch, not `main`.
- Tests need Docker running. Run a single test with `./gradlew test --tests '<class>'`.

## Review Focus

- Adding a book already on a full (200) list answers 204, not 409 (Task 1).
- A non-numeric book id answers 400 (Task 1).
- Staff and admin get 403 on `DELETE` too, not only on `PUT` (Task 1).
- An empty wishlist is an empty page, not 404 (Task 3).
- A book deleted but not yet cleaned up by the async listener is skipped in the list, not a 500 (Task 3).
- One customer never sees another's entries (Task 3).

## File Structure

Create:
- `src/main/java/cz/codingmonkey/bookstore/wishlist/package-info.java`, `Wishlist.java`, `WishlistEntry.java`
- `src/main/java/cz/codingmonkey/bookstore/wishlist/internal/package-info.java`, `WishlistItems.java`, `WishlistService.java`, `WishlistController.java`, `WishlistFullException.java`, `UnknownBookException.java`, `WishlistAccountDeletion.java`
- `src/main/resources/db/migration/V35__create_wishlist_item.sql`
- `src/main/java/cz/codingmonkey/bookstore/storefront/WishlistedBook.java`
- Tests: `src/test/java/cz/codingmonkey/bookstore/wishlist/WishlistIntegrationTest.java`, `WishlistModuleTests.java`
- `docs/design/wishlist.md` already exists.

Modify: `security/SecurityConfiguration.java`, `storefront/StorefrontService.java`, `storefront/StorefrontController.java`, `storefront/package-info.java` (mention wishlist), `README.md`, `docs/design/wishlist.md`.

---

### Task 1: Add and remove books (module, table, PUT/DELETE)

**Files:**
- Create: the `wishlist` package files above except `Wishlist.java`, `WishlistEntry.java`, `WishlistAccountDeletion.java`; `V35__create_wishlist_item.sql`; `WishlistIntegrationTest.java`
- Modify: `SecurityConfiguration.java`

**Interfaces:**
- Produces:
  - table `wishlist_item(account_id BIGINT, book_id BIGINT, added_at TIMESTAMPTZ, PRIMARY KEY (account_id, book_id))` plus index `wishlist_item_book_idx ON (book_id)`.
  - `WishlistItems` (package-private `@Repository`, `JdbcClient`): `boolean exists(AccountId, BookId)`, `int count(AccountId)`, `void insert(AccountId, BookId, Instant addedAt)` (`ON CONFLICT DO NOTHING`), `void delete(AccountId, BookId)`, `int deleteAll(BookId)`, `int deleteAll(AccountId)`.
  - `WishlistService`: `@Transactional void add(AccountId, BookId)`, `@Transactional void remove(AccountId, BookId)`. `static final int LIMIT = 200`.
  - `WishlistFullException` (409, message "Your wishlist is full: at most 200 books"), `UnknownBookException(BookId)` (404, message "No book with id N", same as rating's).

- [ ] **Step 1: Write the failing test** `WishlistIntegrationTest` (`@SpringBootTest(RANDOM_PORT)`, `@AutoConfigureRestTestClient`, `@Import(TestcontainersConfiguration.class)`). Copy `signUp`, `username` and `createBook` helpers from `rating/RatingIntegrationTest`. Check state through `JdbcTemplate` on `wishlist_item`. Tests:
  - `addPutsTheBookOnTheList`: `PUT /api/me/wishlist/{book}` → 204; one row for the customer.
  - `addingTwiceKeepsTheFirstAddedAt`: two PUTs → 204 both, one row, `added_at` unchanged.
  - `removeDeletesTheEntry` and `removingAMissingEntryIsStillNoContent`: both 204.
  - `unknownBookIs404`: PUT for a nonexistent id → 404.
  - `nonNumericBookIdIs400`: `PUT /api/me/wishlist/abc` → 400.
  - `fullListRejectsANewBook`: insert 200 rows for the customer directly with `jdbc` (fake book ids, no FK) → PUT for a real, not listed book → 409 with the full-list detail.
  - `addingAListedBookToAFullListIsNoContent`: 199 fake rows plus the real book via PUT, then 200th fake row, PUT the real book again → 204 (Review Focus).
  - `staffAndAdminAreForbidden`: the autowired `staff` client (admin) gets 403 on PUT and on DELETE; unauthenticated client gets 401.
- [ ] **Step 2: Run to verify failure:** `./gradlew test --tests '*WishlistIntegrationTest'`. Expected: FAIL (404/denied, table missing).
- [ ] **Step 3: Implement** the migration, `package-info.java` files, `WishlistItems`, exceptions and `WishlistService`. `add`: `books.existsAndLockAgainstDeletion(book)` else `UnknownBookException`; return if `exists`; throw `WishlistFullException` if `count >= LIMIT`; `insert` with `Instant.now().truncatedTo(MICROS)`; `changes.info("Account {} added book {} to the wishlist", ...)`. `remove`: `delete`, log only when something was removed (make `delete` return `int`).
- [ ] **Step 4: Implement `WishlistController`** (`@RequestMapping("/api/me/wishlist")`, `@Tag(name = "Wishlist", description = ...)`): `@PutMapping("/{bookId}")` and `@DeleteMapping("/{bookId}")`, both `@ResponseStatus(NO_CONTENT)`, taking `@PathVariable BookId bookId` and `CurrentUser.requireId()`. `@ApiResponse` 204, 404 (PUT), 409 (PUT).
- [ ] **Step 5: Add the security rule** in `SecurityConfiguration`, next to the addresses rule: `.requestMatchers("/api/me/wishlist", "/api/me/wishlist/**").hasRole(Role.CUSTOMER.name())`.
- [ ] **Step 6: Run to verify pass:** `./gradlew test --tests '*WishlistIntegrationTest' --tests cz.codingmonkey.bookstore.ModularityTests`. Expected: PASS.
- [ ] **Step 7: Commit** `feat: add and remove wishlist books`.

---

### Task 2: Cleanup on book and account deletion

**Files:**
- Create: `wishlist/internal/WishlistAccountDeletion.java`, `src/test/java/cz/codingmonkey/bookstore/wishlist/WishlistModuleTests.java`
- Modify: `WishlistService.java`
- Test: also an account-deletion case in `WishlistIntegrationTest`

**Interfaces:**
- Consumes: `WishlistItems.deleteAll(BookId)`, `WishlistItems.deleteAll(AccountId)` (Task 1); `security.AccountDeletionPolicy`.
- Produces: `WishlistService.on(BookDeletedEvent)` (`@ApplicationModuleListener`); `WishlistAccountDeletion implements AccountDeletionPolicy`.

- [ ] **Step 1: Write the failing tests.**
  - `WishlistModuleTests` (`@ApplicationModuleTest`, `@Import(TestcontainersConfiguration.class)`, `Scenario`): insert two rows for a book and one for another book with `JdbcTemplate`; `scenario.publish(new BookDeletedEvent(...))` (check the event's constructor in `bookcatalog`) `.andWaitForStateChange(...)` until the first book has no rows; the other book's row remains. Second test: publish the event twice, no error, still no rows.
  - In `WishlistIntegrationTest`, `deletingACustomerRemovesTheirWishlist`: customer with a wishlist entry and nothing else; admin `DELETE /api/accounts/{id}` (see the existing account deletion tests for the exact call) → the customer's rows are gone, and the account is hard-deleted (a wishlist alone does not force a soft delete).
- [ ] **Step 2: Run to verify failure:** `./gradlew test --tests '*WishlistModuleTests' --tests '*WishlistIntegrationTest'`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** `on(BookDeletedEvent)` in `WishlistService` (delete all for the book; log `"Removed {} wishlist entries of deleted book {}"` when > 0) and `WishlistAccountDeletion`: `holdsRecords` false, `removePersonalData` → `items.deleteAll(account)`, `anonymize` does nothing.
- [ ] **Step 4: Run to verify pass:** same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat: clean up wishlists on book and account deletion`.

---

### Task 3: Read the list (`GET /api/me/wishlist`)

**Files:**
- Create: `wishlist/Wishlist.java`, `wishlist/WishlistEntry.java`, `storefront/WishlistedBook.java`
- Modify: `WishlistItems.java`, `WishlistService.java`, `StorefrontService.java`, `StorefrontController.java`, `storefront/package-info.java`
- Test: `WishlistIntegrationTest.java`

**Interfaces:**
- Consumes: `WishlistItems`, `WishlistService` (Task 1).
- Produces:
  - `public interface Wishlist { Page<WishlistEntry> entriesOf(AccountId account, Pageable page); }`, implemented by `WishlistService` (read-only transaction); `public record WishlistEntry(BookId bookId, Instant addedAt)`.
  - `WishlistItems.page(AccountId, Pageable)` returning `Page<WishlistEntry>`, ordered `added_at DESC, book_id`, ignoring `page.getSort()`, with the total from `count`.
  - `record WishlistedBook(StorefrontBook book, Instant addedAt)` in `storefront`.
  - `StorefrontService.findWishlist(AccountId, Pageable): Page<WishlistedBook>`.

- [ ] **Step 1: Write the failing tests** in `WishlistIntegrationTest`, reading `GET /api/me/wishlist` as JSON (`content[]`, `page.totalElements`):
  - `listsNewestAddedFirstWithPriceAndRating`: add three books at separate times; order is reversed; a priced book shows its `price`, a rated book its `rating`.
  - `aBookWithoutAPriceIsListedWithNullPrice`.
  - `anEmptyWishlistIsAnEmptyPage`: 200 with `content` empty and `totalElements` 0.
  - `pagingSplitsTheList`: 3 books, `size=2` → 2 then 1 items, `totalElements` 3.
  - `customersSeeOnlyTheirOwnList`: two customers.
  - `aDeletedBookIsSkippedBeforeCleanup`: add a book, delete it through the catalog API, immediately GET → 200 and the book is absent (the async cleanup may not have run; the book may not be deleteable if it's ordered, so use a fresh book).
  - `staffAndAdminAreForbidden`: 403 for the admin client.
- [ ] **Step 2: Run to verify failure:** `./gradlew test --tests '*WishlistIntegrationTest'`. Expected: new tests FAIL (404/denied).
- [ ] **Step 3: Implement** `Wishlist`, `WishlistEntry`, `WishlistItems.page`, and `WishlistService implements Wishlist`.
- [ ] **Step 4: Implement `StorefrontService.findWishlist`** with a new `Wishlist` constructor dependency: take the entries page, then in parallel (the `queries` executor, as in `findSimilar`) load the books (`catalog.findBooks(new BookSearch(null, null, null, null).matching(among(ids)), Pageable.unpaged())`), `prices.pricesOf(ids)` and `ratings.summariesOf(ids)`. Map entries in their original order to `WishlistedBook`, skipping entries whose book the catalog no longer has; return `new PageImpl<>(items, page, entries.getTotalElements())`. With no entries, skip the lookups.
- [ ] **Step 5: Add the endpoint** in `StorefrontController`: `@GetMapping("/api/me/wishlist") PagedModel<WishlistedBook> wishlist(Pageable page)` using `CurrentUser.requireId()`; Javadoc summary "Lists your wishlist, newest added first.", `@Tag(name = "Wishlist")`, `@ApiResponse(responseCode = "200", ...)`. Update `storefront/package-info.java` to name `Wishlist`.
- [ ] **Step 6: Run to verify pass:** `./gradlew test --tests '*WishlistIntegrationTest' --tests '*StorefrontIntegrationTest' --tests cz.codingmonkey.bookstore.ModularityTests`. Expected: PASS.
- [ ] **Step 7: Commit** `feat: list the wishlist with prices and ratings`.

---

### Task 4: Docs and full verification

**Files:**
- Modify: `README.md`, `docs/design/wishlist.md`

- [ ] **Step 1: Update README:** module table row (`Wishlist`, `cz.codingmonkey.bookstore.wishlist`, public types `Wishlist`, `WishlistEntry`, purpose), the role table line for `CUSTOMER` ("keep a wishlist"), a "Wishlist" section (endpoints, 204/404/409, the 200-book limit, unpriced books listed with `null` price, cleanup on book and account deletion), the API table rows, and the dependency overview lines (`storefront ──▶ wishlist`, `wishlist ──▶ bookcatalog`, `wishlist implements AccountDeletionPolicy`, and the account deletion bullet that lists the policy implementers). Mention the design doc.
- [ ] **Step 2: Fix the spec:** in `docs/design/wishlist.md` section 2 replace "`WishlistItem` entity (composite key ...)" with a `WishlistItems` `JdbcClient` repository; set status to "implemented (2026-10-18)".
- [ ] **Step 3: Run everything:** `./gradlew build`. Expected: BUILD SUCCESSFUL, all tests pass, `ModularityTests` green.
- [ ] **Step 4: Commit** `docs: describe the wishlist`.
