# Design: auditing purchases

> Note: audit entries now store a generic subject (subject_type + subject_id); see audit-subject.md.

Status: implemented (2026-10-16).

## Goal

Admins can see the whole story of a purchase in the audit log: every step of an order, including stock
reservation, payment and the undo steps, each with the customer who caused it. Today the audit log only shows the
stock movements an order causes, not the order itself.

Agreed with the user:
- Audit **every** step (order, stock reservation, payment, undo).
- Approach **B**: the ordering module reports each step it observes; audit stores all of them under the order id.
  Inventory and payment stay unaware of orders.

## 1. Events

### Ordering: `OrderProgressedEvent` (new, public, `cz.codingmonkey.bookstore.ordering`)

`OrderProgressedEvent(OrderId orderId, OrderStep step, String detail, String actor, Instant occurredAt)`

- `OrderStep` (public enum): `PLACED`, `STOCK_RESERVED`, `STOCK_MISSING`, `PAYMENT_CHARGED`, `PAYMENT_DECLINED`,
  `CONFIRMED`, `REJECTED`, `CANCELLED`, `STOCK_RELEASED`, `PAYMENT_REFUNDED`.
- `detail`: short human-readable text for the step (examples below).
- `actor`: the order's customer (username) for every step, also for steps handled in the background. Since account deletion the actor is the account id (`actorId`), see docs/design/account-deletion.md.
- `occurredAt`: when the step happened, each later than the one before (microseconds), even for steps published
  together. Audit listeners handle steps in parallel, so audit stores this time instead of its own; otherwise
  "confirmed" could be listed before "charged".

Published by:

| Step(s) | Where | Detail example |
|---|---|---|
| `PLACED` | `CartService.checkout` | `3 copies, 38.97 EUR` (copies = sum of line quantities) |
| `STOCK_RESERVED` | `OrderProcess` on `StockReserved` | `Stock reserved` |
| `STOCK_MISSING`, then `REJECTED` | `OrderProcess` on `StockReservationFailed` | `Not enough stock: Sapiens` |
| `PAYMENT_CHARGED`, then `CONFIRMED` | `OrderProcess` on `PaymentSucceeded` | `Charged 38.97 EUR` |
| `PAYMENT_DECLINED`, then `REJECTED` | `OrderProcess` on `PaymentFailed` | `Declined: amounts above 100 are refused` |
| `CANCELLED` | `OrderService.cancel` | `Cancelled by the customer` |
| `STOCK_RELEASED` | `OrderProcess` on `StockReleased` (new listener) | `Stock put back` |
| `PAYMENT_REFUNDED` | `OrderProcess` on `PaymentRefunded` (new listener) | `Refunded 38.97 EUR` |

A step is published only when the order really moves on (the existing state checks in `CustomerOrder`), so a
repeated or late result publishes nothing. `STOCK_RELEASED` and `PAYMENT_REFUNDED` don't change the order's state;
they rely on inventory and payment announcing a release or refund only once (below).

### Inventory: `StockReleased` (new, public)

`StockReleased(ReservationId reservationId)`, published by `StockReservationService` when a release actually puts
stock back (the first time; the reservation row is already locked, so concurrent requests can't both do it).

### Payment: `PaymentRefunded` only once

`PaymentService` publishes `PaymentRefunded` only when `Payment.refund()` actually refunded (today a repeated
refund request publishes it again).

## 2. Audit storage and API

- Migration `V21__add_audit_order.sql`: `audit_entry.order_id BIGINT`; the check constraint becomes
  `num_nonnulls(book_id, author_id, order_id) = 1`.
- `AuditEntry.forOrder(action, orderId, actor, description, occurredAt)`; `OrderId` mapped with a `@Type` (an
  `OrderIdType` must therefore be usable from audit: make the ordering module's `OrderIdType` public in the module's
  API package, like `BookIdType`).
- `AuditEntry.Action` gets `ORDER_PLACED`, `ORDER_STOCK_RESERVED`, `ORDER_STOCK_MISSING`, `ORDER_PAYMENT_CHARGED`,
  `ORDER_PAYMENT_DECLINED`, `ORDER_CONFIRMED`, `ORDER_REJECTED`, `ORDER_CANCELLED`, `ORDER_STOCK_RELEASED`,
  `ORDER_PAYMENT_REFUNDED` (`"ORDER_" + step`).
- `AuditListener.on(Auditable)` (`OrderProgressedEvent` implements `shared.Auditable`): records the entry;
  description = the step's `detail`; actor = `actor`.
- Each step happens once per order, so it is recorded once: `V22__audit_order_step_once.sql` adds a unique index on
  `(order_id, action)`, and the listener skips a step that is already there (an event can be delivered again after a
  crash). The log line for an order step has only the action and order id (no username, no titles).
- `GET /api/audit?orderId=N`; at most one of `bookId`, `authorId`, `orderId` (400 otherwise). Responses get an
  `orderId` field. Newest first; admins only (unchanged).
- Module dependencies: audit → ordering (new). Ordering doesn't depend on audit; no cycle.

## 3. Tests

- Ordering module test: one `OrderProgressedEvent` per step with the right step, detail and actor for the happy
  path, no stock, declined payment and cancel (incl. released/refunded); a repeated result publishes nothing.
- Inventory module test: `StockReleased` exactly once for two concurrent release requests.
- Payment module test: `PaymentRefunded` exactly once for two refund requests.
- Audit module test: an `OrderProgressedEvent` becomes an entry with order id, action, actor and description; the
  database rejects an entry with two subjects.
- HTTP end to end (all modules): `/api/audit?orderId=` shows, newest first, the full sequence for a confirmed then
  cancelled order, a declined order and an out-of-stock order, all by the customer; `bookId` + `orderId` together
  → 400.
- The existing 248 tests and `ModularityTests` still pass.

## Not in scope

Customers reading their own audit trail; auditing account changes, book updates or genre changes.
