# Design: book ratings

Status: proposed (2026-10-17).

## Goal

Customers rate books with 1 to 5 stars. Everyone browsing the store sees each book's average, number of ratings
and how many ratings each star value got.

Agreed with the user:
- **Any customer** (role `CUSTOMER`) may rate any book; buying it first is not required.
- **One rating per customer per book.** Rating again replaces the stars. A customer **cannot remove** a rating;
  ratings disappear only when the book is deleted.
- **Shown on books**: `GET /api/books` and `GET /api/books/{id}` carry the average, the count and the per-star
  breakdown. The MCP tools `search_books` and `get_book` return the same JSON, so AI clients see ratings too.
- Stars only: no review text.

## 1. Module `rating`

A new Spring Modulith module `cz.codingmonkey.bookstore.rating`. It depends on `bookcatalog` (book ids, the
existence check, `BookDeletedEvent`) and `shared` only. `storefront` depends on it, as it does on `pricing`;
neither catalog nor pricing knows about ratings.

Public API (module root package):

```java
/** Read access to book ratings for other modules. */
public interface Ratings {

    RatingSummary summaryOf(BookId bookId);

    /** Summaries of the given books; every requested book is in the map, unrated ones with RatingSummary.NONE. */
    Map<BookId, RatingSummary> summariesOf(Collection<BookId> bookIds);
}

/**
 * @param average mean stars rounded to one decimal (HALF_UP), {@code null} when nobody rated the book
 * @param count   number of ratings
 * @param stars   number of ratings per star value; always has keys 1 to 5 in ascending order
 */
public record RatingSummary(@Nullable BigDecimal average, int count, Map<Integer, Integer> stars) {
    public static final RatingSummary NONE = ...; // average null, count 0, every star 0
}
```

Internal (`rating.internal`): `RatingController`, `RatingService` (implements `Ratings`), `BookRatings` (SQL),
`RatingRequest`, `RatingResponse`, `RatingNotFoundException`, `UnknownBookException`. Both packages get a
`package-info.java` with `@NullMarked`; the root one also `@ApplicationModule(displayName = "Ratings")`.

## 2. Table

Migration `V25__create_book_rating.sql`:

```sql
-- Owned by the rating module. Customers are referenced by username (accounts belong to the security module).
CREATE TABLE book_rating (
    book_id  BIGINT                   NOT NULL,
    customer VARCHAR(100)             NOT NULL,
    stars    SMALLINT                 NOT NULL CHECK (stars BETWEEN 1 AND 5),
    rated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (book_id, customer)
);
```

No foreign key to `book`: modules don't reference each other's tables (as `book_price` and `cart_line`). The
primary key starts with `book_id`, so it also serves the per-book summary and the delete by book.

No JPA entity. `BookRatings` uses `JdbcClient`, like `recommendation.BookEmbeddings`:
- save: `INSERT ... ON CONFLICT (book_id, customer) DO UPDATE SET stars = ..., rated_at = ...`. Two parallel
  first ratings by the same customer can't fail on the key; the later one wins.
- find one customer's rating of a book;
- counts: `SELECT book_id, stars, count(*) ... WHERE book_id IN (...) GROUP BY book_id, stars`. One query per page
  of books; average and count are computed from these rows in Java;
- delete all ratings of a book.

## 3. REST API

All under the book, for the signed-in customer (`Principal.getName()` is the username, as in ordering). New
controller `RatingController` with `@Tag(name = "Ratings")`.

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `PUT /api/books/{id}/rating` | `{"stars": 4}` | 200, `RatingResponse` | 400 stars missing or not 1-5; 404 no such book |
| `GET /api/books/{id}/rating` | | 200, `RatingResponse` | 404 the customer hasn't rated this book |

`RatingResponse(BookId bookId, int stars, Instant ratedAt)`. `RatingRequest(@NotNull @Min(1) @Max(5) Integer stars)`
validated with `@Valid`.

`GET` returns 404 for an unknown book as well as for an unrated one (`RatingNotFoundException`, detail "You haven't
rated this book"); no catalog lookup is needed.

`PUT` runs in one transaction: `BookCatalog.existsAndLockAgainstDeletion(bookId)` (row lock `FOR SHARE`, as restock
and set-price), then the upsert. Unknown book → `UnknownBookException` (404, "No book with id 7"). A delete that
started first holds `FOR UPDATE`, so the check waits and then sees no book; a delete that starts later waits until
the rating is committed and then its `BookDeletedEvent` removes it.

Changes are logged through `ChangeLog` ("Customer {} rated book {} with {} stars"). No audit entry and no event:
nothing else needs to react to a rating.

OpenAPI: Javadoc on methods and records, `@ApiResponse` per status including 200.

### Security

In `SecurityConfiguration`, before the staff rule for `/api/books/**`:

```java
// Customers: rate books.
.requestMatchers(GET, "/api/books/*/rating").hasRole(Role.CUSTOMER.name())
.requestMatchers(PUT, "/api/books/*/rating").hasRole(Role.CUSTOMER.name())
```

The public `GET /api/books/*` rule doesn't match `/api/books/7/rating` (`*` is one path segment), so order relative
to it doesn't matter; the customer rules must come before `/api/books/**` → `STAFF`. Any other method on `/api/books/*/rating` (e.g.
`DELETE`) then falls to the staff rule; for customers that is 403. Staff and admins don't have `CUSTOMER`, so they
can't rate.

## 4. Storefront

`StorefrontBook` gets a last component `RatingSummary rating` (never null; `NONE` for unrated books). JSON:

```json
"rating": {"average": 4.3, "count": 12, "stars": {"1": 0, "2": 1, "3": 0, "4": 3, "5": 8}}
```

- `findBook`: the rating summary is a third parallel query next to book and price.
- `findBooks`: after the page query, `ratings.summariesOf(ids)` together with `prices.pricesOf(ids)` (both need
  only the ids, so they run in parallel on the existing virtual-thread executor).

`StorefrontMcpTools` descriptions mention the rating fields. The storefront module's Javadoc names `Ratings` next to
`Prices`.

No sorting or filtering by rating, and no caching (the summary query is one indexed `GROUP BY` per page).

## 5. Book deletion

`RatingService.on(BookDeletedEvent)` with `@ApplicationModuleListener` deletes all ratings of the book. Running it
twice deletes nothing the second time, so redelivery is harmless. Ratings don't veto deletes (no
`BookDeletionPolicy`).

## 6. Tests

- `RatingIntegrationTest` (HTTP, `RANDOM_PORT`, `RestTestClient` as a registered customer):
  rate → 200 and `GET` returns it; rate again → stars replaced, count stays 1; stars 0, 6 and missing → 400;
  unknown book → 404 on `PUT`; `GET` before rating → 404; two customers' ratings show on `GET /api/books/{id}`
  and in `GET /api/books` as average, count and breakdown; an unrated book shows `average: null, count: 0`.
- Security (in the same test or the existing security test): anonymous `PUT` → 401; staff `PUT` → 403; customer
  `DELETE /api/books/{id}/rating` → 403.
- `RatingModuleTests` (`@ApplicationModuleTest`, `Scenario`): publish `BookDeletedEvent` → the book's ratings are
  gone, other books' ratings stay.
- `ModularityTests` passes (no cycle: storefront → rating → bookcatalog).
- Storefront tests that compare whole JSON bodies are updated for the new `rating` field.

## 7. Docs

README: a "Ratings" section under Modules, the two endpoints in the API table, the `rating` field in the storefront
section, and the module list. CLAUDE.md: add `rating` to the module list.
