# Design: personal picks and rating sort/filter

Status: implemented (2026-10-17).

## Goal

Make ratings useful beyond the summary on each book:

1. Anyone browsing can filter the book list by rating and sort it by rating.
2. A signed-in customer gets personal picks: books like the ones they rated highly.

Agreed with the user:
- **Picks come from similar content**: the embeddings the recommendation module already keeps, not other
  customers' ratings (collaborative filtering needs many overlapping raters, which the store doesn't have).
- **Only liked books count**: books the customer rated 4 or 5 stars. Lower ratings only keep a book out of the
  picks; they don't push the picks away from it.
- **Without liked books, fall back to the top-rated books**, marked as not personal.
- **Only books for sale** are picked. Out-of-stock books stay in: they are still for sale.
- **Sorting by rating uses a damped average**, so 100 ratings averaging 4.8 rank above a single 5-star rating. The
  displayed average stays the plain one.
- **The catalog gets a sort extension point** (`BookOrder`), next to the existing filter one (`BookCondition`).

Delivered in two parts, in this order, since the fallback reuses the ranking of part 1:
part 1 is sections 1-3, part 2 is section 4.

## 1. API: `GET /api/books`

Two new optional query parameters, also on the `search_books` MCP tool:

| Parameter | Values | Meaning |
|---|---|---|
| `minRating` | decimal from 1 to 5, e.g. `4` or `4.5` | Only books whose displayed average (rounded to one decimal, half up, as in `RatingSummary`) is at least this. Unrated books never match. Outside 1-5: 400. |
| `orderBy` | `title` (default), `rating`; any case | `rating`: best ranked first (see section 3), unrated books last; ties by title, then id. Anything else: 400. |

Both combine with the existing filters (AND), including the price range. `sort` stays ignored, and the fixed default
order is still by title. Matching the rounded average means a book shown as 4.0 matches `minRating=4` even when its
exact average is 3.96.

The storefront module parses `orderBy` into its own enum (`BookOrdering { TITLE, RATING }`) and documents it as an
enum in OpenAPI. `minRating` is validated with `@DecimalMin("1")`/`@DecimalMax("5")`, so a bad value gets the usual
400 problem detail with `errors`. The MCP tool checks the same and returns a tool error.

## 2. Catalog: `BookOrder` and combined conditions

New public SPI in `bookcatalog`, beside `BookCondition`:

```java
/**
 * Extension point for other modules to sort a {@link BookSearch} by their own data, typically with a correlated
 * scalar subquery. The catalog adds its own tie-breakers (title, then id) after it.
 */
@FunctionalInterface
public interface BookOrder {

    /**
     * @param bookId the id of the book being sorted, to correlate a subquery with
     * @param query  the catalog's query, to create subqueries from
     */
    Order toOrder(Expression<BookId> bookId, AbstractQuery<?> query, CriteriaBuilder cb);
}
```

`BookSearch` gets a last component `@Nullable BookOrder order` and `ordered(BookOrder)`, returning a copy. The
existing four-argument constructor stays.

`BookSearch.matching(condition)` changes from replacing the condition to **adding** it: with one already set, the new
condition is `cb.and(existing, added)`. Today only the price range uses it, so nothing changes for existing callers,
but price range plus `minRating` would otherwise lose one of them.

When the search has an order, `BookSpecifications` sets `query.orderBy(order, asc(title), asc(id))` and the catalog
runs the query with an unsorted page (the page's own sort would replace the specification's order). Spring Data
removes a specification's order from the count query; the first test of the plan checks that, so paging totals stay
right. Without an order, nothing changes: the caller's `Sort.by("title")` is used as today.

## 3. Rating: filter, ranking and reads for recommendation

### Ranking

A book's rank score is a Bayesian (damped) average:

    score = (C × m + sum of the book's stars) / (C + number of the book's ratings)

- `m` is the mean of all ratings in the store, read once when the order is created.
- `C = 5` (constant `RatingRanking.PRIOR_WEIGHT`): a book's score counts as five extra ratings of `m`. With
  `m = 4`, one 5-star rating scores 4.17 and a hundred ratings averaging 4.8 score 4.76.
- Unrated books have no score (`NULL`) and sort last. With no ratings in the store at all, every score is `NULL`,
  so `orderBy=rating` returns the title order.

### Public API additions (`Ratings`)

```java
/** Books whose displayed average (one decimal, half up) is at least {@code minAverage}; unrated books never match. */
BookCondition ratedAtLeast(BigDecimal minAverage);

/** Books by rank score, best first, unrated last. Reads the store-wide mean when called. */
BookOrder byRating();

/** The customer's stars per book; empty if they rated nothing. */
Map<BookId, Integer> ratingsBy(String customer);

/** Rated books by rank score, best first, ties by book id; a window of {@code count} starting at {@code offset}. */
List<BookId> topRated(int offset, int count);
```

`ratingsBy` takes the username as the rating module stores it (the principal's name, as for `PUT /rating`).
`topRated` breaks ties by id rather than title, since the rating module doesn't know titles.

### Implementation

`ratedAtLeast` and `byRating` are criteria subqueries correlated with the catalog's book id. The filter is an
`EXISTS` with `HAVING 20 × sum(stars) >= k × count(*)`, where `k = 2 × ceil(10 × minRating) − 1`: in integers, this
is exactly "average rounded half up to one decimal ≥ minRating", which `round(avg(...))` on a floating-point average
gets wrong at the boundary (4.05). The order is the score expression above as a scalar subquery, computed in floating
point, descending with nulls last (JPA 3.2 `Nulls.LAST`). Criteria subqueries need an entity, so `rating.internal` gets a
read-only `@Immutable` entity on `book_rating` (composite id `book_id`, `customer`; `BookId` mapped with
`BookIdType`). Writes stay in `BookRatings` with `JdbcClient`. `ratingsBy`, `topRated` and the store-wide mean are
plain SQL in `BookRatings`.

No migration: the primary key `(book_id, customer)` already serves the per-book aggregates.

### Storefront

`StorefrontService.findBooks` takes the rating filter and the ordering besides the price range. A price range and
`minRating` each add their condition (`matching`), `orderBy=rating` adds `ratings.byRating()` (`ordered`), and
the page is unsorted in that case. Prices and rating summaries are still fetched for the page afterwards, in
parallel, as now. The controller and `StorefrontMcpTools.searchBooks` pass the new parameters through.

## 4. Personal picks

### Endpoint

`GET /api/recommendations?limit=10`, customers only (`CUSTOMER`). The signed-in customer is implied, as for
`/api/cart` and `/api/orders`. `limit` is 1-20, default 10; otherwise 400.

```json
{"personal": true,
 "books": [{"bookId": 7, "title": "Children of Dune", "authors": ["Frank Herbert"]}]}
```

`books` are best first, with fewer than `limit` when there aren't enough candidates (possibly none).
`personal: false` means the list is the top-rated fallback. Records: `PersonalPicks(boolean personal,
List<PickedBook> books)` and `PickedBook(BookId bookId, String title, List<String> authors)`. There is no score: the
fallback has no similarity to show, and the order says enough.

No MCP tool: MCP is anonymous, so there is no customer.

### Algorithm (`recommendation`, new `PersonalPicksService`)

1. `ratings.ratingsBy(customer)`. **Liked**: 4 or 5 stars. **Excluded**: every rated book.
2. **Taste vector**: `avg(embedding)` (pgvector) over the liked books' rows that aren't deleted. If no liked book
   has an embedding yet (just created), go to step 5.
3. **Candidates**: books not deleted and not excluded, by cosine distance to the taste vector, nearest first, read
   in batches of `2 × limit` (`OFFSET`/`LIMIT`).
4. **For sale**: keep a batch's books that `prices.pricesOf(ids)` returns. Repeat 3-4 until `limit` books are kept
   or a batch comes back short. Return them with `personal: true`.
5. **Fallback**: walk `ratings.topRated(offset, 2 × limit)` in the same way, dropping excluded books, books not for
   sale and books without a live embedding row (titles and authors come from `book_embedding`, which already
   stores them, so no catalog call). Return with `personal: false`. No ratings in the store: empty list.

An HNSW index scan stops after `hnsw.ef_search` rows (40 by default), so with excluded books and offsets the
nearest-neighbour query could silently return too few. The candidate query therefore runs in a read-only
transaction with `SET LOCAL hnsw.iterative_scan = strict_order` (pgvector 0.8+; the plan checks the version in
`pgvector/pgvector:pg17`). The existing `/similar` query is unchanged.

The taste vector is computed per request: one `avg` over a handful of rows. Picks aren't cached.

### Dependencies

New: `recommendation → rating` (`Ratings`) and `recommendation → pricing` (`Prices`). No cycle: rating and pricing
depend only on `bookcatalog` and `shared`.

### Security

`SecurityConfiguration`: `GET /api/recommendations` → `CUSTOMER`. Anonymous gets 401, staff and admins 403.
`/api/books` stays public with the new parameters.

## 5. Documentation

- OpenAPI: Javadoc on the new method and records, the controller's existing `@Tag` "Recommendations",
  `@ApiResponse` for 200 and 400, `orderBy` and `minRating` described on the books list.
- `search_books` MCP description: the two new parameters.
- README: Storefront (filter and sort by rating; drop "There is no sorting or filtering by rating"),
  Recommendations (personal picks and the fallback), the API table, the MCP table and the dependency diagram.
- CLAUDE.md: `BookOrder` next to `BookDeletionPolicy` in the patterns list.

## 6. Tests

All against real Postgres and the real embedding model, as the existing recommendation tests.

- **Catalog** (first, before anything relies on it): a search with a `BookOrder` and a condition pages in that order
  and reports the right totals (count query without the order); two `matching` calls AND their conditions.
- **`RatingSortIntegrationTest`** (HTTP): `minRating` uses the rounded average; unrated never match; 0.9 and 5.1 →
  400; `orderBy=rating` ranks many 4.8-star ratings above one 5-star, unrated last, and is the title order with no
  ratings at all; combines with a price range; `orderBy=price` → 400; MCP `search_books` with both parameters.
- **`PersonalPicksIntegrationTest`** (HTTP as a registered customer; books *Dune*, *Children of Dune*, a cookbook):
  liking *Dune* puts *Children of Dune* before the cookbook; rated books never appear; a book without a price never
  appears; with only low ratings → top-rated fallback with `personal: false`; no ratings anywhere → empty list;
  `limit` 0 and 21 → 400; anonymous → 401; staff → 403.
- **Batching**: with more books not for sale than `2 × limit` nearest to the taste vector, the picks are still
  filled from later batches.
- `ModularityTests` passes with the new dependencies.

## Out of scope

- "Because you liked X" explanations.
- Leaving out out-of-stock books.
- Collaborative filtering.
- Caching picks or the store-wide mean.
- A stored per-book rating summary (count and sum). It can replace the aggregate subqueries later without an API
  change.
