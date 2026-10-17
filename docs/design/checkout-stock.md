# Design: stock check at checkout, cart restored on rejection

Status: implemented (2026-10-17).

## Goal

A simulation with 10 customers showed two problems with ordering:

1. Checkout empties the cart right away. When the order is rejected later (background stock reservation or
   payment), the customer has to fill the cart again from scratch.
2. One book short of stock rejects the whole order, and the customer only learns it after checkout.

Agreed with the user:
- **Check stock at checkout.** If any cart book has fewer copies in stock than the cart wants, checkout answers
  409 naming the short books and how many are left. No order is placed and the cart stays as it is.
- Orders stay **all-or-nothing**. Inventory still decides under its row locks; a rejection for stock now only
  happens if stock runs out between the check and the reservation.
- **Put the lines back on rejection.** When an order is rejected (stock or payment), its lines go back into the
  customer's cart. Cancelled orders don't (the customer chose that).

## 1. Inventory: `Stock` (public API)

```java
/** Read access to stock levels for other modules. */
public interface Stock {

    /** Copies in stock per book; every requested book is in the map, with 0 when it has no stock record. */
    Map<BookId, Integer> availableOf(Collection<BookId> bookIds);
}
```

Implemented by `inventory.internal.InventoryService` (read-only transaction, plain `findAllById`, no lock).
Ordering already depends on inventory's events, so this adds no cycle.

## 2. Checkout

`CartService.checkout`, after the existing for-sale check (books locked against deletion, prices fixed):

- `stock.availableOf(cart book ids)`; a line is short when available < quantity.
- Any short line → `OutOfStockException` (`@ResponseStatus(CONFLICT)`), message
  `Not enough stock: Cosmos (0 left), Dune (2 left). Change the quantities and check out again.` (titles in cart
  order). The transaction rolls back: the cart is unchanged, no order, no events.
- Not-for-sale is checked first (its message stays as today); the stock check only runs when every book is for sale.

The check reads without locking. If another order takes the stock before this order's reservation, inventory
rejects the order as before (`STOCK_MISSING`, `REJECTED`) and section 3 refills the cart.

## 3. Cart restored on rejection

`OrderProcess` rejects in two places: `on(StockReservationFailed)` and `on(PaymentFailed)`. In the same
transaction, right after the order moves to `REJECTED`, it calls `CartService.restore(order)`:

- Loads the customer's cart with a row lock (`CartRepository.findAndLockById`, `PESSIMISTIC_WRITE`), creating it if
  missing. The lock makes a concurrent cart edit by the customer wait and then fail its version check (409 "changed
  at the same time", as today) instead of failing the listener, which would leave the rejection unprocessed.
- For each order line: `Cart.add(bookId, quantity)`: adds to the quantity already in the cart, capped at
  `MAX_QUANTITY` (20); a new book beyond `MAX_LINES` (50) is skipped.
- Books no longer for sale are put back too; the cart shows them without a price and checkout refuses them, as for
  any cart.
- Once per order: the process only moves an order to `REJECTED` once (`stockUnavailable` / `paymentFailed` return
  false on repeats), so a redelivered event restores nothing.
- Logged via `ChangeLog`: `Order {}: put {} lines back into the cart`.

No new event or audit step.

## 4. Tests

- Inventory (`InventoryModuleTests` or a new test): `availableOf` returns stock levels, 0 for a book without a
  record, every requested id present.
- Ordering module tests (`OrderingModuleTests`, `Stock` mocked):
  - checkout with a short line throws `OutOfStockException` with the exact message; cart unchanged; no order saved;
  - `StockReservationFailed` → order `REJECTED` and the cart holds the order's lines again, added to a line the
    customer put in meanwhile, capped at 20;
  - `PaymentFailed` → cart restored too;
  - the same `StockReservationFailed` delivered twice → quantities added once.
- HTTP (`OrderingIntegrationTest`):
  - `missingStockRejectsTheOrderWithoutTakingAnything` becomes: checkout with 2 wanted, 1 in stock → 409, detail
    `Not enough stock: <title> (1 left). ...`, cart unchanged, no new order, stock unchanged;
  - `declinedPaymentRejectsTheOrderAndPutsStockBack` additionally: the cart holds the order's lines again.
- `ModularityTests` pass.

## 5. Docs

`docs/design/orders.md` (checkout and rejection), README Orders section and API row for checkout (409 for short
stock), `CLAUDE.md` module patterns if needed.
