An online bookstore. Anyone can browse the catalog with prices and get recommendations; customers keep a cart and
place orders; staff maintain the catalog, prices and stock; admins manage accounts and read the audit log.

## Signing in

Every request signs in with **HTTP Basic** (use *Authorize* above); there are no sessions or tokens. Customers sign up
themselves with `POST /api/accounts/register`. Staff and admin accounts are created by an admin.

| Role | May |
|------|-----|
| anyone | Browse books, authors, genres, prices and recommendations; sign up |
| `CUSTOMER` | Their own account, cart and orders |
| `STAFF` | Also maintain books, authors, genres, prices and stock; see all orders |
| `ADMIN` | Everything staff may, plus manage accounts and read the audit log |

Wrong credentials get `401` even on public endpoints. A signed-in account without the needed role gets `403`.

## Conventions

- **Ids** are positive integers. A malformed or non-positive id in a path or query parameter is a `400`.
- **Money** is `{"amount": 12.90, "currency": "EUR"}`. The store sells in one currency (EUR), and amounts always
  have its decimal places.
- **Lists** are pages: `?page=0&size=20` (0-based, at most 100 per page) returning
  `{"content": [...], "page": {"size": 20, "number": 0, "totalElements": 57, "totalPages": 3}}`. Each list has a
  fixed order, given in its description.
- **Errors** are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details (`application/problem+json`).
  `detail` says what went wrong; invalid requests also list each invalid field or parameter under `errors`:

  ```json
  {"title": "Bad Request", "status": 400, "detail": "Invalid request content.", "instance": "/api/books",
   "errors": [{"field": "title", "message": "must not be blank"}]}
  ```

- **Concurrent changes**: when another request changed the same data at the same moment, the loser gets `409`.
  Reload and retry.

## Buying books

1. Put books in the cart with `PUT /api/cart/items/{bookId}` (only books with a price are for sale).
2. `POST /api/cart/checkout` fixes titles and prices, empties the cart and answers `202` with the `PENDING` order.
3. Stock is reserved and the payment charged in the background. Poll `GET /api/orders/{id}` until the order is
   `CONFIRMED` or `REJECTED` (out of stock, or payment declined; `reason` says which).
4. A confirmed order can be cancelled (`POST /api/orders/{id}/cancel`): the stock goes back and the payment is
   refunded.

The payment provider is simulated: by default it declines orders above 100 EUR, so the failure path is easy to try.
