# Book Ratings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Customers rate books 1-5 stars (one replaceable rating each); books show average, count and per-star
breakdown.

**Architecture:** New Spring Modulith module `rating` (depends on `bookcatalog`, `shared`) owns table `book_rating`,
accessed with `JdbcClient` (upsert). It exposes `Ratings` for other modules and a customer-only REST resource
`/api/books/{id}/rating`. `storefront` adds a `RatingSummary` to every `StorefrontBook`, fetched in parallel with
prices.

**Tech Stack:** Java 25, Spring Boot 4, Spring Modulith (`@ApplicationModuleListener`, `Scenario`), `JdbcClient`,
Flyway, Testcontainers pgvector Postgres, Gradle.

**Spec:** `docs/design/book-ratings.md`

## Global Constraints

- Branch `book-ratings`; one commit per task, message ends with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Migration `src/main/resources/db/migration/V25__create_book_rating.sql` (latest today is V24). Never edit older ones.
- Every new package has `package-info.java` with `@org.jspecify.annotations.NullMarked`.
- Lines ≤ 120 characters; typed ids (`BookId`), never bare `long` in APIs.
- Mutations log through `shared.ChangeLog` (`Customer {} rated book {} with {} stars`).
- Errors: exceptions with `@ResponseStatus` and a client-facing message (it becomes the problem `detail`).
- New endpoints: Javadoc for OpenAPI, `@ApiResponse` per status including 200, controller `@Tag(name = "Ratings")`.
- `average`: `BigDecimal`, one decimal, `RoundingMode.HALF_UP`; `null` when count is 0. `stars`: keys 1..5 ascending,
  zero-filled.
- Test command: `./gradlew test --tests '<pattern>'` (Docker must run).

## Review Focus

- Same customer signing in with different username case (`BEA` vs `bea`) must still have one rating:
  `Principal.getName()` is the stored username. Test in Task 2.
- `{"stars": 4.5}` must be 400, not silently 4. Test in Task 2; fix by
  `spring.jackson.deserialization.accept-float-as-int: false` in `application.yml` if Jackson coerces.
- Rating a book being deleted: after delete commits, `PUT` must be 404 and no orphan rows remain. Task 1 (listener)
  and Task 2 (404 after delete).
- A page mixing rated and unrated books: unrated ones get `NONE`, never missing from the map. Task 1.
- Staff/admin `PUT` must be 403 (they lack `CUSTOMER`); `DELETE` must be 403 for customers. Task 2.

---

### Task 1: Rating module core (table, API, service, deletion listener)

**Files:**
- Create: `src/main/resources/db/migration/V25__create_book_rating.sql` (SQL exactly as spec §2)
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/package-info.java`
  (`@NullMarked`, `@ApplicationModule(displayName = "Ratings")`, Javadoc: customers' star ratings of books)
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/Ratings.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/RatingSummary.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/package-info.java` (`@NullMarked`)
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/BookRatings.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/RatingService.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/UnknownBookException.java`
- Test: `src/test/java/cz/codingmonkey/bookstore/rating/internal/RatingModuleTests.java` (in `internal`, like
  `inventory/internal/StockReservationModuleTests`, so it can call the package-private service)

**Interfaces:**
- Produces (public):
  - `public interface Ratings { RatingSummary summaryOf(BookId bookId);
    Map<BookId, RatingSummary> summariesOf(Collection<BookId> bookIds); }`
  - `public record RatingSummary(@Nullable BigDecimal average, int count, Map<Integer, Integer> stars)` with
    `public static final RatingSummary NONE` and `static RatingSummary of(Map<Integer, Integer> countsByStars)`
    (fills missing stars with 0, computes count and average; `stars` is an unmodifiable ordered map).
- Produces (internal, used by Task 2):
  - `record Rating(BookId bookId, int stars, Instant ratedAt)` in `BookRatings` file or its own file
    `rating/internal/Rating.java`.
  - `@Service class RatingService implements Ratings` (package-private) with
    `@Transactional Rating rate(BookId bookId, String customer, int stars)` (throws `UnknownBookException`),
    `@Transactional(readOnly = true) Optional<Rating> find(BookId bookId, String customer)`, and
    `@ApplicationModuleListener void on(BookDeletedEvent event)`.
  - `@ResponseStatus(NOT_FOUND) class UnknownBookException(BookId)`; message `"No book with id " + bookId.value()`.
  - `@Repository class BookRatings(JdbcClient)`: `Rating save(BookId, String customer, int stars, Instant ratedAt)`
    (`INSERT ... ON CONFLICT (book_id, customer) DO UPDATE SET stars = EXCLUDED.stars, rated_at = EXCLUDED.rated_at`),
    `Optional<Rating> find(BookId, String customer)`, `Map<BookId, Map<Integer, Integer>> countStars(Collection<BookId>)`
    (`GROUP BY book_id, stars` with `book_id = ANY(?)`), `int deleteAll(BookId)`.
- Consumes: `BookCatalog.existsAndLockAgainstDeletion(BookId)`; `BookDeletedEvent(BookId bookId, String isbn, actor)`.

- [ ] **Step 1: Failing tests** in `RatingModuleTests` (`@ApplicationModuleTest`, `@Import(TestcontainersConfiguration.class)`,
  `@MockitoBean BookCatalog catalog` returning `true` for `existsAndLockAgainstDeletion` by default,
  `@BeforeEach` `DELETE FROM book_rating` via `JdbcTemplate`):
  - `summarizesRatings`: `rate(DUNE,"a",4)`, `rate(DUNE,"b",4)`, `rate(DUNE,"c",5)` →
    `summaryOf(DUNE)` = average `new BigDecimal("4.3")`, count 3, stars `{1=0,2=0,3=0,4=2,5=1}` (assert keys in order).
  - `roundsHalfUp`: ratings 4,4,4,5 (avg 4.25) → `4.3` (HALF_EVEN would give 4.2); ratings 1 and 2 → `1.5`;
    ratings 4,5,5,5,5,5 (avg 4.8333) → `4.8`;
    ratings 1,1,2 (avg 1.3333) → `1.3`; ratings 4,5,5 (avg 4.6667) → `4.7`.
  - `ratingAgainReplacesTheStars`: rate 2 then 5 by `"a"` → count 1, average `5.0`, `find(DUNE,"a")` stars 5.
  - `unratedBooksHaveNoAverage`: `summaryOf(DUNE)` equals `RatingSummary.NONE` (average null, count 0, all zeros).
  - `summariesCoverEveryRequestedBook`: rate DUNE only; `summariesOf(List.of(DUNE, NEUROMANCER))` has both keys,
    NEUROMANCER → `NONE`.
  - `rejectsUnknownBooks`: catalog returns `false` → `rate` throws `UnknownBookException`; no row stored.
  - `bookDeletionRemovesItsRatings(Scenario)`: rate DUNE and NEUROMANCER;
    `scenario.publish(new BookDeletedEvent(DUNE, "9780441013593", "staff")).andWaitForStateChange(() ->
    service.summaryOf(DUNE).count(), c -> c == 0)`; then NEUROMANCER count still 1. Publishing it again changes nothing.
- [ ] **Step 2:** Run `./gradlew test --tests '*RatingModuleTests'` → FAIL (compilation: classes missing).
- [ ] **Step 3:** Add migration, public types, `BookRatings`, `RatingService`, `UnknownBookException`. `rate` checks
  the catalog first, then saves with `Instant.now()` truncated to microseconds, then `ChangeLog`.
- [ ] **Step 4:** Run `./gradlew test --tests '*RatingModuleTests'` → PASS (7 tests); then
  `./gradlew test --tests cz.codingmonkey.bookstore.ModularityTests` → PASS.
- [ ] **Step 5:** Commit `Ratings: module, table and summaries`.

### Task 2: REST endpoint and security

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/RatingController.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/RatingRequest.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/RatingResponse.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/RatingNotFoundException.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/security/SecurityConfiguration.java` (customer rules before
  `/api/books/**` staff rule, comment `// Customers: rate books.`)
- Maybe modify: `src/main/resources/application.yml` (only if the 4.5 test fails, see Review Focus)
- Modify: `README.md` (API table rows, Security role table: `CUSTOMER` may rate books; Modules table row)
- Test: `src/test/java/cz/codingmonkey/bookstore/rating/RatingIntegrationTest.java`
- Test: `src/test/java/cz/codingmonkey/bookstore/security/SecurityIntegrationTest.java` (one new test)

**Interfaces:**
- Consumes: `RatingService.rate/find`, `Rating`, `UnknownBookException` (Task 1).
- Produces (HTTP):
  - `PUT /api/books/{id}/rating` body `RatingRequest(@NotNull @Min(1) @Max(5) Integer stars)` → 200
    `RatingResponse(BookId bookId, int stars, Instant ratedAt)`; 400; 404 unknown book.
  - `GET /api/books/{id}/rating` → 200 `RatingResponse`; 404 `RatingNotFoundException`
    (message `"You haven't rated this book"`).
  - Controller `@RequestMapping("/api/books/{id}/rating")`, `@Tag(name = "Ratings", description = ...)` saying
    customers (`CUSTOMER`) rate 1-5 stars, one rating per book, rating again replaces it, ratings can't be removed;
    customer = `Principal.getName()`.

- [ ] **Step 1: Failing tests** in `RatingIntegrationTest` (`RANDOM_PORT`, `@AutoConfigureRestTestClient`,
  `TestcontainersConfiguration`; admin `client` creates a book via `POST /api/books`; customers via a `signUp(username)`
  helper copied from `OrderingIntegrationTest`):
  - `customerRatesABook`: `PUT {"stars":4}` → 200, `$.bookId` = book, `$.stars` = 4, `$.ratedAt` exists;
    `GET` → 200, `$.stars` = 4.
  - `ratingAgainReplacesIt`: PUT 2 then PUT 5 → `GET` stars 5; `SELECT count(*) FROM book_rating WHERE book_id = ?`
    (via `JdbcTemplate`) = 1.
  - `sameCustomerInAnyLetterCase`: sign up `bea-xxxx`, PUT 3 with that client, PUT 5 with a client signed in as
    `BEA-XXXX` (same password) → row count 1, stars 5.
  - `rejectsInvalidStars`: bodies `{"stars":0}`, `{"stars":6}`, `{}`, `{"stars":4.5}` → each 400, content type
    `application/problem+json`.
  - `unknownBookIs404`: PUT on `/api/books/999999999/rating` → 404, `$.detail` = `No book with id 999999999`.
  - `deletedBookIs404`: create book, delete it (admin), PUT → 404.
  - `notRatedYetIs404`: GET before rating → 404, `$.detail` = `You haven't rated this book`.
- [ ] **Step 2: Failing test** in `SecurityIntegrationTest`: `onlyCustomersRateBooks` — anonymous PUT → 401; staff PUT
  → 403; admin PUT → 403; a signed-up customer `DELETE /api/books/1/rating` → 403; anonymous GET → 401.
- [ ] **Step 3:** Run `./gradlew test --tests '*RatingIntegrationTest' --tests '*SecurityIntegrationTest'` → FAIL.
- [ ] **Step 4:** Add controller, records (Javadoc on components for OpenAPI), `RatingNotFoundException`, security
  rules (`GET` and `PUT` on `/api/books/*/rating` → `hasRole(CUSTOMER)`). If only the 4.5 case fails, add
  `spring.jackson.deserialization.accept-float-as-int: false` to `application.yml`.
- [ ] **Step 5:** Run the Step 3 command → PASS; then `./gradlew test --tests '*OpenApiIntegrationTest'` → PASS.
- [ ] **Step 6:** Update README (Modules row for Ratings, roles table, two API rows, short "### Ratings" section
  under Modules mirroring spec §1, §3, §5). Add `rating` to the module list in `CLAUDE.md`.
- [ ] **Step 7:** Commit `Ratings: customers rate books over REST`.

### Task 3: Ratings on storefront books and MCP

**Files:**
- Modify: `src/main/java/cz/codingmonkey/bookstore/storefront/StorefrontBook.java` (last component
  `RatingSummary rating`, `@param rating` Javadoc; `of(BookDetails, @Nullable Money, RatingSummary)`)
- Modify: `src/main/java/cz/codingmonkey/bookstore/storefront/StorefrontService.java` (inject `Ratings`)
- Modify: `src/main/java/cz/codingmonkey/bookstore/storefront/StorefrontMcpTools.java` (descriptions)
- Modify: `src/main/java/cz/codingmonkey/bookstore/storefront/package-info.java` (Javadoc names `Ratings`)
- Modify: `README.md` (Storefront section: `rating` field with the JSON example from spec §4; MCP table `search_books`
  / `get_book` mention ratings)
- Test: `src/test/java/cz/codingmonkey/bookstore/storefront/StorefrontModuleTests.java`
- Test: `src/test/java/cz/codingmonkey/bookstore/storefront/StorefrontIntegrationTest.java`

**Interfaces:**
- Consumes: `Ratings.summaryOf`, `Ratings.summariesOf`, `RatingSummary.NONE` (Task 1); `PUT /api/books/{id}/rating`
  (Task 2) in the integration test.

- [ ] **Step 1: Failing tests** in `StorefrontModuleTests`: add `@MockitoBean Ratings ratings`; in `@BeforeEach`
  stub `summaryOf(any())` → `NONE` (a mock's `null` would break the non-null component). `summariesOf` needs no
  default: the mock's empty map means every book gets `NONE`.
  - Extend `fetchesBookAndPriceInParallel` to a three-party `CyclicBarrier(3)` including `ratings.summaryOf(DUNE)`;
    assert `book.rating()` is the stubbed summary.
  - `listsBooksWithRatings`: page of DUNE; `summariesOf` returns DUNE → `RatingSummary.of(Map.of(5, 2))`;
    result's `rating().average()` = `5.0`, `count()` = 2.
- [ ] **Step 2: Failing test** in `StorefrontIntegrationTest`: `showsRatings` — two signed-up customers rate a book 4
  and 5; `GET /api/books/{id}` → `$.rating.average` 4.5, `$.rating.count` 2, `$.rating.stars.4` 1,
  `$.rating.stars.5` 1, `$.rating.stars.1` 0; `GET /api/books?title={prefix}` → the rated book has the same rating
  and an unrated book has `$.content[1].rating.average` empty and `.count` 0.
- [ ] **Step 3:** Run `./gradlew test --tests 'cz.codingmonkey.bookstore.storefront.*'` → FAIL.
- [ ] **Step 4:** Implement: `findBook` adds `supplyAsync(() -> ratings.summaryOf(id), queries)`; `findBooks` runs
  `pricesOf(ids)` and `summariesOf(ids)` via two `supplyAsync` calls and `join` both; missing map entry →
  `RatingSummary.NONE`. MCP descriptions: `search_books` lists `rating ({average, count, stars})` among returned
  fields; `get_book` says "with its price and rating".
- [ ] **Step 5:** Run the Step 3 command → PASS; then `./gradlew build` → BUILD SUCCESSFUL (all tests, incl.
  `ModularityTests`, `McpServerIntegrationTest`, `OpenApiIntegrationTest`).
- [ ] **Step 6:** Update README (Storefront section, MCP table). Set spec status to
  `implemented (<date>)`.
- [ ] **Step 7:** Commit `Storefront: show ratings on books`.
