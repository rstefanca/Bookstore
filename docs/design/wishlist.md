# Design: wishlist

Status: proposed (2026-10-18).

## Goal

A customer keeps a plain list of books they want to remember: add, remove, list. The list shows each book with its
current price and rating, as the storefront does.

Agreed with the user:
- **Plain list first.** Move-to-cart is the next feature and is out of scope here. The design must not block it:
  `ordering` can later depend on `wishlist`'s public API without a cycle.
- **Customers only** (`CUSTOMER`). Staff and admins get 403 and hold no wishlist.
- **Owned by account id**, one entry per book per account.
- **Books without a list price stay on the list** with a `null` price. They are not hidden and cannot be rejected
  when added; they may become buyable later.
- **At most 200 books per wishlist.**
- **A new `wishlist` module**, composed with the catalog, prices and ratings by `storefront` at the edge. Catalog,
  pricing and rating don't know the wishlist.

## 1. API

| Request | Answer |
|---|---|
| `PUT /api/me/wishlist/{bookId}` | 204. Adds the book. Idempotent: adding a book already on the list changes nothing and keeps its `addedAt`. |
| `DELETE /api/me/wishlist/{bookId}` | 204. Removes the book; also 204 when it isn't on the list. |
| `GET /api/me/wishlist` | A page of items, newest added first (then by book id). Fixed order, `sort` ignored; size default 20, max 100. |

Each item of the `GET` page:

```json
{ "book": { ...same shape as a storefront book (title, authors, genres, price or null, rating summary)... },
  "addedAt": "2026-10-18T09:30:00Z" }
```

Errors:
- `PUT` for a book that doesn't exist: 404.
- `PUT` for a new book when the list already has 200: 409 ("Your wishlist is full: at most 200 books"). Adding a
  book already on a full list still answers 204.
- Staff and admins on any of the three: 403. Unauthenticated: 401.

`PUT` sends no body. Every endpoint needs `@Tag`, Javadoc for the summary and params, `@ApiResponse` for each status
including 204, and `@ResponseStatus(NO_CONTENT)` where it applies.

## 2. Module `wishlist`

Public API (module root package):

```java
public interface Wishlist {
    /** The books on the account's wishlist, newest added first. */
    Page<WishlistEntry> entriesOf(AccountId account, Pageable page);
}
public record WishlistEntry(BookId bookId, Instant addedAt) {}
```

Internal (`wishlist.internal`): `WishlistItem` entity (composite key account + book, `addedAt`), repository,
`WishlistService`, `WishlistController`, `WishlistFullException` (409), `UnknownBookException` (404, as in `rating`),
`WishlistAccountDeletion`, and a listener for `BookDeletedEvent`.

Behavior:
- **Add** (in one transaction): lock the book with `BookCatalog.existsAndLockAgainstDeletion` (404 if false), then
  insert if absent. The count check and insert race when one customer sends two adds at once; both are rare and
  harmless, so the primary key keeps the entry unique and the limit may be exceeded by one in that race at most.
  Not worth a lock; documented here on purpose.
- **Remove**: delete the row if present.
- **Book deleted**: `@ApplicationModuleListener` on `BookDeletedEvent` deletes every entry for the book. Duplicate
  delivery is harmless (the delete is idempotent). An add racing the delete is prevented by the `FOR KEY SHARE`
  lock, as for ratings.
- **Account deleted**: `AccountDeletionPolicy` implementation. `holdsRecords` is false (a wishlist is not a record
  worth keeping), `removePersonalData` deletes the account's entries, `anonymize` does nothing more. The same
  hard-delete/soft-delete decision as for other modules is unaffected.
- **Logging**: each add and remove goes through `ChangeLog` with the account id and book id. No `Auditable` event.

## 3. Database

Migration `V35__create_wishlist_item.sql`:

```sql
CREATE TABLE wishlist_item (
    account_id BIGINT      NOT NULL,
    book_id    BIGINT      NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, book_id)
);
CREATE INDEX wishlist_item_book_idx ON wishlist_item (book_id);
```

No foreign keys: modules own their tables and reference others by id only. The book index serves the delete
cleanup. Listing orders by `added_at DESC, book_id`, which the account-prefixed primary key serves well enough for
lists capped at 200.

## 4. Storefront composition

`storefront` gets the page of `WishlistEntry` from `Wishlist`, then loads those books through `BookCatalog`, prices
through `Prices` and ratings through `Ratings`, with the same assembly that builds `StorefrontBook`. The controller
lives in `storefront` for `GET /api/me/wishlist`; `PUT`/`DELETE` live in `wishlist`. The page's totals and paging come
from the wishlist query. A book that disappeared between the two reads (delete racing the cleanup) is skipped, so
the page may briefly have fewer items than its size.

## 5. Security

In `SecurityConfiguration`, all three requests need `hasRole(CUSTOMER)`:
`GET`, `PUT`, `DELETE` on `/api/me/wishlist` and `/api/me/wishlist/*`.

## 6. Tests

- `WishlistIntegrationTest` (HTTP, `RANDOM_PORT`): add, idempotent add keeps `addedAt`, remove, remove of a missing
  entry, listing order and paging, a book with no price listed with `null` price, 404 for an unknown book, 409 at
  the limit and 204 for re-adding on a full list, 403 for staff and admin, 401 anonymous, one customer never sees
  another's list.
- `WishlistModuleTests` (`@ApplicationModuleTest`, `Scenario`): publishing `BookDeletedEvent` removes the entries,
  also when delivered twice.
- Account deletion: a customer with a wishlist and nothing else is hard-deleted and the entries are gone.
- `ModularityTests` passes with the new module.

## 7. Docs

README: add `wishlist` to the module table, a "Wishlist" section, the API table rows and the dependency overview
(`storefront ──▶ wishlist`, `wishlist ──▶ bookcatalog`, `wishlist implements AccountDeletionPolicy`). The
`package-info.java` of both new packages carries `@NullMarked`; the module root carries `@ApplicationModule`.

## Out of scope

- Move-to-cart (the next feature).
- Price-drop and back-in-stock notifications.
- Sharing a wishlist, several lists, notes on items.
- An MCP tool for the wishlist.
- Caching the list.
