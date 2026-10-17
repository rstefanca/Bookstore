# Personal Picks and Rating Sort/Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/books` filters by `minRating` and sorts by `orderBy=rating` (damped average); customers get
personal picks at `GET /api/recommendations` from the embeddings of books they rated 4-5 stars.

**Architecture:** The catalog gets a sort SPI `BookOrder` next to `BookCondition`; the rating module supplies a
condition and an order as JPA criteria subqueries over a read-only entity on `book_rating`. Storefront wires both into
the book list. The recommendation module averages liked books' embeddings in pgvector, walks nearest neighbours in
batches and keeps books for sale (`Prices`), falling back to `Ratings.topRated`.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JPA Specifications (Hibernate 7, JPA 3.2 `Nulls`), `JdbcClient` /
`JdbcTemplate`, pgvector 0.8.7 (`pgvector/pgvector:pg17`), Spring Modulith, Testcontainers.

**Spec:** `docs/design/personal-picks.md`

## Global Constraints

- Branch `personal-picks`; one commit per task, message ends with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- No migration. Never edit existing migrations.
- Damped score `(C × m + Σstars) / (C + n)`, `C = 5` (`RatingRanking.PRIOR_WEIGHT`), `m` = mean of all ratings,
  computed in floating point; unrated books `NULL`, sorted last; then title asc, then id asc.
- `minRating` 1-5 inclusive, compared with the displayed average (one decimal, half up); unrated never match.
- `orderBy`: `title` (default) or `rating`, case-insensitive; anything else 400.
- Picks: liked = 4 or 5 stars; excluded = every rated book; only books with a price; batches of `2 × limit`;
  `limit` 1-20, default 10; customers only.
- Lines ≤ 120 characters; typed ids in APIs; new endpoints need Javadoc, `@ApiResponse` per status including 200,
  and a rule in `SecurityConfiguration`.
- Test command: `./gradlew test --tests '<pattern>'` (Docker must run).

## Review Focus

- A book averaging exactly 4.05 is shown as 4.1, so it must match `minRating=4.1`; one averaging 3.95 (shown 4.0)
  must match `minRating=4`. SQL `round(avg(...))` on a double gets 4.05 wrong. Test in Task 2.
- Integer division in the score (`bigint / bigint`) would truncate 4.76 and 4.5 to 4 and fall back to title order.
  Test in Task 2 with titles in the opposite order to the ratings.
- Liked books without an embedding yet: `avg(embedding)` is `NULL`, and `ORDER BY embedding <=> NULL` returns rows in
  arbitrary order instead of failing. Must fall back to top-rated. Test in Task 4.
- A customer who rated every book, or a `limit` above the number of candidates: fewer books, and the batch loop
  stops (a batch shorter than requested ends it). Test in Task 4.
- A customer signing in with different username case (`BEA` vs `bea`) must still have their rated books excluded:
  pass `Principal.getName()`, as `RatingController` does. Test in Task 5.

---

### Task 1: Catalog sort SPI and combined conditions

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/bookcatalog/BookOrder.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/bookcatalog/BookSearch.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/bookcatalog/internal/BookSpecifications.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/bookcatalog/internal/BookService.java:53-56` (`findAll`)
- Test: `src/test/java/cz/codingmonkey/bookstore/bookcatalog/internal/BookSearchOrderIntegrationTest.java`

**Interfaces:**
- Produces: `public interface BookOrder { Order toOrder(Expression<BookId> bookId, AbstractQuery<?> query,
  CriteriaBuilder cb); }` (`jakarta.persistence.criteria.Order`), Javadoc as spec §2.
- Produces: `BookSearch(..., @Nullable BookCondition condition, @Nullable BookOrder order)`, existing 4-arg
  constructor kept, `BookSearch ordered(BookOrder order)`, `BookSearch matching(BookCondition condition)` now ANDs.

- [ ] **Step 1: Write the failing tests** (`@SpringBootTest`, `@Import(TestcontainersConfiguration.class)`, autowire
  `BookCatalog`; create 3 books through `BookService` or the REST client, titles `A`, `B`, `C` with unique suffixes and
  a shared title marker to filter by)

```java
@Test void ordersByTheOtherModulesOrderThenTitle()   // order = (id, q, cb) -> cb.desc(id); page size 2
    // page 0 = [C, B], page 1 = [A]; totalElements 3, totalPages 2 (count query must not carry the order)
@Test void matchingTwiceAndsTheConditions()          // condition 1 excludes A, condition 2 excludes C -> [B]
@Test void matchingOnceKeepsTheConditionItself()     // search.matching(c).condition() == c (same instance)
```

- [ ] **Step 2: Run** `./gradlew test --tests '*BookSearchOrderIntegrationTest'` — FAIL (no `ordered`).
- [ ] **Step 3: Implement.** `matching`: no condition yet → the condition itself (keeps `StorefrontModuleTests`'
  equality stubbing working); otherwise a new `BookCondition` returning `cb.and(old, added)`. `BookSpecifications.
  matching`: when `search.order() != null`, add a specification that calls `query.orderBy(order.toOrder(id, query,
  cb), cb.asc(book.get("title")), cb.asc(book.get("id")))` and returns `cb.conjunction()`. `BookService.findAll`: with
  an order, call the repository with `PageRequest.of(page.getPageNumber(), page.getPageSize())` (unsorted, so Spring
  Data keeps the specification's order); otherwise unchanged.
- [ ] **Step 4: Run** the new test and `'*PaginationIntegrationTest'`, `'*StorefrontModuleTests'` — PASS.
- [ ] **Step 5: Commit** `Catalog: BookOrder sort extension point; conditions combine with AND`.

### Task 2: Rating condition, ranking and reads

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/RatedBook.java` (read-only entity)
- Create: `src/main/java/cz/codingmonkey/bookstore/rating/internal/RatingRanking.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/rating/Ratings.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/rating/internal/RatingService.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/rating/internal/BookRatings.java`
- Test: `src/test/java/cz/codingmonkey/bookstore/rating/internal/RatingRankingIntegrationTest.java`

**Interfaces:**
- Consumes: `BookOrder`, `BookSearch.matching/ordered`, `BookCatalog.findBooks` (Task 1).
- Produces on `Ratings`: `BookCondition ratedAtLeast(BigDecimal minAverage)`, `BookOrder byRating()`,
  `Map<BookId, Integer> ratingsBy(String customer)`, `List<BookId> topRated(int offset, int count)`.
- Produces: `RatingRanking.PRIOR_WEIGHT = 5` (package-private constant used by both SQL and criteria).

- [ ] **Step 1: Write the failing tests** (`@SpringBootTest`; create books through the catalog; insert ratings
  directly with `JdbcTemplate` into `book_rating (book_id, customer, stars, rated_at)` with customers `c1`..`cN`;
  `DELETE FROM book_rating` in `@BeforeEach`)

```java
@Test void ratedAtLeastUsesTheDisplayedAverage()
    // X: 20 ratings, nineteen 4 + one 5 (avg 4.05, shown 4.1) -> matches 4.1
    // Y: 20 ratings, nineteen 4 + one 3 (avg 3.95, shown 4.0) -> matches 4, not 4.1
    // Z: unrated -> never matches, not even 1
@Test void byRatingRanksTheDampedAverage()
    // A: eight 5 + two 4 (10, sum 48)   B: one 5   C: 4, 5, 5 (sum 14)   D: unrated   E: five 1
    // m = 72/19 = 3.789 -> A 4.463, C 4.118, B 3.991, E 2.395 -> expected [A, C, B, E, D]
    // (title order is A..E, plain average B, A, C, E; integer division gives A, B, C, E, D: all fail)
@Test void byRatingWithoutAnyRatingsIsTheTitleOrder()
@Test void ratingsByReturnsTheCustomersStars()       // c1 rated X 5 and Y 2 -> {X=5, Y=2}; unknown -> empty
@Test void topRatedWalksRatedBooksBestFirst()        // same data: topRated(0, 2) = [A, C], topRated(2, 2) = [B, E]
```

- [ ] **Step 2: Run** `./gradlew test --tests '*RatingRankingIntegrationTest'` — FAIL.
- [ ] **Step 3: Implement `RatedBook`**: `@Entity @Immutable @Table(name = "book_rating")`, composite id of
  `bookId` (`BookId`, `@Type(BookIdType.class)` as other entities map it) and `customer`; `short stars` (the column is
  `SMALLINT`; `int` fails `ddl-auto: validate`). No other columns mapped.
- [ ] **Step 4: Implement in `RatingService`** (all `@Transactional(readOnly = true)` where they query):
  - `ratedAtLeast(min)`: `k = 2 × ceil(10 × min) − 1` (BigDecimal, exact); condition is
    `EXISTS (select 1 from RatedBook r where r.bookId = <book> group by r.bookId
    having 20 × sum(r.stars) >= k × count(r))` — integers only, equivalent to "rounded half up ≥ min".
  - `byRating()`: `m = bookRatings.meanStars()` (0 when no ratings); order is
    `cb.desc(<scalar subquery>, Nulls.LAST)` where the subquery selects
    `(C·m + sum(stars as Double)) / (C + count(r) as Double)` for `r.bookId = <book>` (null when unrated).
  - `ratingsBy(customer)`, `topRated(offset, count)` delegate to `BookRatings`.
- [ ] **Step 5: Implement in `BookRatings`**: `double meanStars()` (`SELECT coalesce(avg(stars), 0)`),
  `Map<BookId, Integer> starsBy(String customer)`, `List<BookId> topRated(double mean, int offset, int count)`:
  `GROUP BY book_id ORDER BY (C * mean + sum(stars))::float8 / (C + count(*)) DESC, book_id OFFSET ? LIMIT ?`.
- [ ] **Step 6: Run** the new test, `'*RatingIntegrationTest'`, `'*RatingModuleTests'` — PASS.
- [ ] **Step 7: Commit** `Ratings: filter, damped ranking and reads for other modules`.

### Task 3: Book list filter and sort by rating

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/storefront/BookOrdering.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/storefront/InvalidOrderingException.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/storefront/StorefrontService.java` (`findBooks`)
- Modify: `src/main/java/cz/codingmonkey/bookstore/storefront/StorefrontController.java` (`list`)
- Modify: `src/main/java/cz/codingmonkey/bookstore/storefront/StorefrontMcpTools.java` (`searchBooks`)
- Modify: `src/test/java/cz/codingmonkey/bookstore/storefront/StorefrontModuleTests.java` (new signature)
- Modify: `README.md` (Storefront, API table row for `GET /api/books`, MCP table `search_books`), `CLAUDE.md`
  (SPI bullet: `BookOrder` lets storefront sort by rating)
- Test: `src/test/java/cz/codingmonkey/bookstore/storefront/RatingSortIntegrationTest.java`

**Interfaces:**
- Consumes: `Ratings.ratedAtLeast`, `Ratings.byRating` (Task 2); `BookSearch.matching/ordered` (Task 1).
- Produces: `enum BookOrdering { TITLE, RATING; static BookOrdering parse(String value) }` (case-insensitive, throws
  `InvalidOrderingException`: `@ResponseStatus(BAD_REQUEST)`, message
  `"orderBy must be title or rating, was %s"`); `Page<StorefrontBook> findBooks(BookSearch search,
  PriceRange priceRange, @Nullable BigDecimal minRating, BookOrdering ordering, Pageable page)`.

- [ ] **Step 1: Write the failing tests** (HTTP; ratings inserted with `JdbcTemplate` as in Task 2; prices via
  `PUT /api/books/{id}/price` as admin)

```java
@Test void filtersByMinimumRating()        // ?minRating=4 -> books shown >= 4.0 only; unrated absent
@Test void rejectsMinRatingOutOfRange()    // 0.9 and 5.1 -> 400 problem with errors[].field "minRating"
@Test void sortsByRating()                 // ?orderBy=rating and ?orderBy=RATING -> damped order, unrated last
@Test void rejectsUnknownOrdering()        // ?orderBy=price -> 400, detail "orderBy must be title or rating, was price"
@Test void combinesRatingAndPriceFilters() // ?minRating=4&maxPrice=10 -> only books matching both
@Test void mcpSearchBooksFiltersAndSorts() // call search_books with minRating and orderBy as StorefrontMcpTools bean
```

- [ ] **Step 2: Run** `./gradlew test --tests '*RatingSortIntegrationTest'` — FAIL.
- [ ] **Step 3: Implement.** Controller params: `@RequestParam(required = false) @Nullable @DecimalMin("1")
  @DecimalMax("5") BigDecimal minRating`, `@RequestParam(defaultValue = "title") @Parameter(schema =
  @Schema(allowableValues = {"title", "rating"})) String orderBy`; Javadoc `@param`s for both; `@ApiResponse(400)`
  description extended with the rating cases. With `RATING`, the page passed down is unsorted; with `TITLE`,
  `Sort.by("title")` as today. Service adds `ratings.ratedAtLeast` via `matching` and `ratings.byRating()` via
  `ordered`. MCP: params `minRating` (`"Lowest average rating, 1-5, e.g. 4"`) and `orderBy`
  (`"title (default) or rating (best rated first)"`); out-of-range `minRating` → `IllegalArgumentException`;
  description says books are sorted by title unless `orderBy` is `rating`.
- [ ] **Step 4: Update docs**: README Storefront section (replace "There is no sorting or filtering by rating." with
  the parameters, the damped score formula with `C = 5`, unrated last, rounding rule), API table, MCP table; CLAUDE.md.
- [ ] **Step 5: Run** `'*RatingSortIntegrationTest'`, `'*Storefront*'`, `'*ModularityTests'` — PASS.
- [ ] **Step 6: Commit** `Storefront: filter and sort books by rating`.

### Task 4: Personal picks service

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/recommendation/PersonalPicksService.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/recommendation/PersonalPicks.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/recommendation/PickedBook.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/recommendation/BookEmbeddings.java`
- Test: `src/test/java/cz/codingmonkey/bookstore/recommendation/PersonalPicksServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `Ratings.ratingsBy`, `Ratings.topRated` (Task 2); `Prices.pricesOf(Collection<BookId>)`.
- Produces: `record PersonalPicks(boolean personal, List<PickedBook> books)`,
  `record PickedBook(BookId bookId, String title, List<String> authors)` (Javadoc for OpenAPI, package-private like
  `SimilarBook`); `PersonalPicks PersonalPicksService.picksFor(String customer, int limit)`.
- Produces on `BookEmbeddings`: `boolean anyExists(Collection<BookId> ids)`,
  `List<PickedBook> nearestTo(Collection<BookId> liked, Collection<BookId> excluded, int offset, int count)`,
  `List<PickedBook> findLive(List<BookId> ids)` (in the given order, deleted/missing left out).

- [ ] **Step 1: Write the failing tests** (`@SpringBootTest`; books *Dune*, *Children of Dune*, *Dune Messiah*,
  *The Joy of Cooking* created via REST as in `RecommendationControllerIntegrationTest`, await embeddings; prices via
  REST; ratings via `JdbcTemplate`)

```java
@Test void likedBookPullsSimilarBooksFirst()   // c likes Dune 5; all priced -> personal, first = a Dune sequel,
                                               // cookbook after both sequels; Dune absent
@Test void leavesOutRatedAndUnpricedBooks()    // c likes Dune 5, rated Messiah 2; Children unpriced
                                               // -> [The Joy of Cooking]
@Test void fillsFromLaterBatches()             // limit 1 (batch 2); both sequels unpriced -> [The Joy of Cooking]
@Test void stopsWhenCandidatesRunOut()         // c rated all but one -> 1 book with limit 20
@Test void fallsBackToTopRatedWithoutLikes()   // c rated cookbook 2; others rate Messiah 5, Children 4 ->
                                               // personal false, [Messiah, Children], cookbook absent
@Test void fallsBackWhenLikedBooksHaveNoEmbedding() // DELETE the liked book's book_embedding row -> personal false
@Test void emptyWithoutAnyRatings()            // fresh customer, no ratings in store -> personal false, []
```

- [ ] **Step 2: Run** `./gradlew test --tests '*PersonalPicksServiceIntegrationTest'` — FAIL.
- [ ] **Step 3: Implement `BookEmbeddings.nearestTo`** with `@Transactional(readOnly = true)`: first
  `SET LOCAL hnsw.iterative_scan = strict_order`, then
  `ORDER BY embedding <=> (SELECT avg(embedding) FROM book_embedding WHERE book_id = ANY(?) AND NOT deleted)`
  over rows `NOT deleted AND book_id <> ALL(?)`, `OFFSET ? LIMIT ?`. `anyExists`/`findLive` are plain queries.
- [ ] **Step 4: Implement `picksFor`**: liked/excluded from `ratingsBy`; if liked non-empty and `anyExists(liked)`,
  fill from `nearestTo` → `personal true`; else fill from `topRated` + `findLive`, minus excluded → `personal false`.
  One private fill loop for both sources:

```text
kept = []; offset = 0
loop: batch = source(offset, 2 × limit); offset += batch.size()   // batch = candidate ids (and rows)
      priced = prices.pricesOf(ids of batch).keySet()
      kept += batch rows whose id is priced (and, for the fallback, not excluded), until kept.size() == limit
      stop when kept.size() == limit or batch.size() < 2 × limit
```

- [ ] **Step 5: Run** the new test, `'*Recommendation*'`, `'*ModularityTests'` — PASS (new dependencies
  `recommendation → rating, pricing` allowed, no cycle).
- [ ] **Step 6: Commit** `Recommendations: personal picks from liked books, top-rated fallback`.

### Task 5: Personal picks endpoint

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/recommendation/PersonalPicksController.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/security/SecurityConfiguration.java` (customer rules block)
- Modify: `README.md` (Recommendations section, API table, dependency diagram, pgvector 0.8+ note under running
  against your own Postgres)
- Test: `src/test/java/cz/codingmonkey/bookstore/recommendation/PersonalPicksIntegrationTest.java`

**Interfaces:**
- Consumes: `PersonalPicksService.picksFor(String, int)`, `PersonalPicks` (Task 4).
- Produces: `GET /api/recommendations?limit=` → `PersonalPicks` JSON
  `{"personal": true, "books": [{"bookId": 7, "title": "...", "authors": ["..."]}]}`.

- [ ] **Step 1: Write the failing tests** (HTTP; customers signed up via `/api/accounts/register` as in
  `RatingIntegrationTest`)

```java
@Test void customerGetsPicksFromTheirRatings()  // rate Dune 5 via PUT /rating -> 200, personal true, Dune absent
@Test void sameCustomerInAnyLetterCase()        // sign up "bea-<n>", rate as "BEA-<N>", picks as lowercase exclude it
@Test void rejectsLimitOutOfRange()             // limit 0 and 21 -> 400
@Test void anonymousIs401()
@Test void staffIs403()                         // demo-less: create a STAFF account as admin, then GET -> 403
```

- [ ] **Step 2: Run** `./gradlew test --tests '*PersonalPicksIntegrationTest'` — FAIL (401/403 mismatch, 404).
- [ ] **Step 3: Implement.** Controller: `@RestController`, existing `@Tag(name = "Recommendations")` text extended
  with "and personal picks for customers"; method `PersonalPicks picks(Principal principal,
  @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit)`, Javadoc first sentence "Picks books for the
  signed-in customer from the books they rated 4 or 5 stars.", `@ApiResponse` 200 and 400. Security: add
  `.requestMatchers(GET, "/api/recommendations").hasRole(Role.CUSTOMER.name())` beside the cart/orders rule.
- [ ] **Step 4: Update README**: Recommendations section (personal picks, liked = 4-5 stars, top-rated fallback with
  `personal: false`, only books for sale, needs pgvector 0.8+ for `hnsw.iterative_scan`), API table row, Security
  table (customers: personal picks), dependency diagram lines `recommendation ──Ratings.ratingsBy/topRated──▶ rating`
  and `recommendation ──Prices.pricesOf──▶ pricing`. Set the spec's status line to "implemented (<date>)".
- [ ] **Step 5: Run** `./gradlew build` — BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 6: Commit** `Recommendations: GET /api/recommendations for customers`.
