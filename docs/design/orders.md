# Design: cart, orders and fake payment

Status: implemented (2026-10-16).

## Goal

Customers put books in a cart, check out, and get an order. The order goes through stock reservation and a
(fake) payment. Each step can fail, and earlier steps are undone. This is the showcase for Spring Modulith:
modules working together through events, with undo, retries and tests that drive the whole flow.

Decisions already made: cart first; fake payment; asynchronous, event-driven flow; customers can cancel a
confirmed order (stock back, payment refunded); staff can list all orders; no shipping yet.

## Modules

| Module | New/changed | Owns |
|--------|-------------|------|
| `ordering` (new) | new | carts and orders; runs the order process |
| `payment` (new) | new | fake charges and refunds |
| `inventory` | changed | stock reservations (take stock for an order, give it back) |

Dependencies go one way: `ordering` → `bookcatalog`, `pricing`, `inventory`, `payment`, `shared`.
`inventory` and `payment` know nothing about orders. They offer a public "request → result" event contract that
`ordering` uses. (If inventory listened to `OrderPlacedEvent` *and* ordering listened to inventory's reply,
the two modules would depend on each other, which Modulith rejects.)

## Cart (`ordering`)

- Table `cart_item` (customer username, book id, quantity, added at); one cart per customer.
- `GET /api/cart`: items with current title and price, line totals, cart total. Books no longer for sale
  are shown with `price: null`.
- `PUT /api/cart/items/{bookId}` `{quantity}` (1–20; 0 removes), `DELETE /api/cart/items/{bookId}`,
  `DELETE /api/cart`. Adding checks the book exists and is for sale (`BookCatalog.findBook`, `Prices.priceOf`).
  Stock is *not* checked here, only at checkout.
- `POST /api/cart/checkout`: snapshots titles and prices, creates the order (`PENDING`), empties the cart,
  answers `202 Accepted` with the order and `Location: /api/orders/{id}`. Empty cart or a book no longer for
  sale → `409` with the reason, cart unchanged. Since 2026-10-17 checkout also reads stock (`inventory.Stock`)
  and answers `409` naming each short book and how many are left, cart unchanged
  (`docs/design/checkout-stock.md`). Since 2026-10-18 checkout also needs a shipping and an invoice address (from the
  optional request body or the customer's saved ones, else `400` before stock is touched) and the order keeps a copy
  of both (`docs/design/customer-addresses.md`).

## Orders (`ordering`)

Tables `customer_order` (id, customer, status, total amount + currency, reason, created at, version; since V28 also the `shipping_*` and `invoice_*` groups
(name, street, city, postal code, country), nullable and all-or-nothing per group, null for orders placed earlier) and
`order_line` (order id, book id, title, quantity, unit price).

States:

```
PENDING ──stock reserved──▶ AWAITING_PAYMENT ──paid──▶ CONFIRMED ──customer cancels──▶ CANCELLED
   │                              │
   └─no stock──▶ REJECTED         └─payment failed──▶ REJECTED (stock released)
```

API (customers, own orders only; another customer's order is `404`):
- `GET /api/orders` (paged, newest first), `GET /api/orders/{id}` (status, lines, total, reason).
- `POST /api/orders/{id}/cancel`: only `CONFIRMED` (`409` otherwise). Releases stock and refunds.
- Staff: `GET /api/orders/all` (paged, all customers).

## The order process (events)

`ordering` contains an `OrderProcess` that reacts to each step (all `@ApplicationModuleListener`: async, after
commit, tracked in the event registry, retried after a restart):

1. Checkout publishes `inventory.StockReservationRequested(reservationId = order id, lines)`.
2. `inventory` reserves **all lines or none** and publishes `StockReserved` or
   `StockReservationFailed(reason, missing book ids)`.
3. On `StockReserved`: order → `AWAITING_PAYMENT`, publish `payment.PaymentRequested(reference = order id,
   amount)`. On `StockReservationFailed`: order → `REJECTED` ("Not enough stock: Dune"), and its lines go back
   into the customer's cart.
4. `payment` charges (fake) and publishes `PaymentSucceeded` or `PaymentFailed(reason)`.
5. On `PaymentSucceeded`: order → `CONFIRMED`. On `PaymentFailed`: order → `REJECTED`, its lines go back into
   the cart, publish `StockReleaseRequested` (undo step 2).
6. Cancel: order → `CANCELLED`, publish `StockReleaseRequested` and `RefundRequested`.

Every event that the order process publishes also carries the customer as `actor`, so stock changes in the
audit log show who caused them.

**Safe under retries and duplicates.** Events can arrive twice (retries) or late. Each handler checks the
order's state and ignores events that don't fit it (e.g. `StockReserved` for an order that is no longer
`PENDING`). Reservations and payments are keyed by the order id, so repeating a request returns the earlier
result instead of reserving or charging twice. The undo results (`StockReleased`, `PaymentRefunded`) don't change
the order's state, so the order keeps a flag for each (`stock_released`, `payment_refunded`, set by a conditional
update that doesn't touch the version) and reports the undo step only the first time.

## Inventory changes

- Tables `stock_reservation` (id = reservation id, status `RESERVED`/`RELEASED`) and `stock_reservation_line`.
- Reserving locks the needed stock rows (`SELECT ... FOR UPDATE`, in book id order to avoid deadlocks), checks
  all lines, then withdraws them; any shortage → nothing changes, `StockReservationFailed`. Two orders for the
  last copy: exactly one gets it.
- Releasing restocks the reserved lines once (`RELEASED` makes it idempotent).
- Stock changes still publish `StockAdjustedEvent`, so audit and logs see them.

## Payment module (fake)

- Table `payment` (reference, amount, currency, status `CHARGED`/`DECLINED`/`REFUNDED`).
- Declines when the amount is above `bookstore.payment.decline-above` (default **100 EUR**), so the failure path
  is easy to try. Otherwise charges.
- Refund marks `REFUNDED` and publishes `PaymentRefunded`.

## Security

`/api/cart/**`, `/api/orders`, `/api/orders/{id}`, `/api/orders/{id}/cancel` → role `CUSTOMER`.
`/api/orders/all` → `STAFF` (and admins). The customer is the signed-in username (`shared.CurrentUser`).

## Database

Flyway `V18__create_cart_and_orders.sql`, `V19__create_stock_reservation.sql`, `V20__create_payment.sql`.

## Tests

- **Ordering module test** (only `ordering` running; inventory and payment replies published by the test with
  Modulith `Scenario`): happy path, no stock, payment declined (stock release requested), cancel (release +
  refund), duplicate and late events ignored, wrong-state cancel `409`; checkout refuses short stock (cart kept);
  rejected orders (stock or payment) put their lines back into the cart, once.
- **Inventory module test**: all-or-nothing reservation, release, idempotency, race for the last copy.
- **Payment module test**: charge, decline over limit, refund, idempotency.
- **Cart tests** (HTTP): add/change/remove, not-for-sale books, checkout snapshot, empty cart.
- **End to end** (HTTP, all modules): customer signs up, fills cart, checks out, order becomes `CONFIRMED`,
  stock goes down; cancel → stock back, payment `REFUNDED`; order over 100 EUR → `REJECTED`, stock unchanged;
  not enough stock → checkout `409`, cart kept, no order; payment declined → books back in the cart; other
  customer can't see the order; staff list.
- `ModularityTests` confirms the dependency direction.

## Not in this step

Shipping, real payment provider, emails, order changes after checkout, stock reservations that expire.
