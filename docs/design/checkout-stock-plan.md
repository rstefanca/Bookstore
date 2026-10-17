# Checkout Stock Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (chosen: native execution, one
> whole-branch review at the end). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Checkout refuses short stock up front (409, cart kept); rejected orders put their lines back in the cart.

**Architecture:** Inventory exposes `Stock.availableOf`; `CartService.checkout` compares it with the cart before
placing the order. `OrderProcess` calls `CartService.restore` in the same transaction as each rejection, under a
cart row lock.

**Tech Stack:** Java 25, Spring Boot 4, Spring Modulith, JPA, Testcontainers, Gradle.

**Spec:** `docs/design/checkout-stock.md`

## Global Constraints

- Branch `checkout-stock-check`; one commit per task, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 409 message exactly: `Not enough stock: <title> (<n> left), <title> (<n> left). Change the quantities and check out again.`
- Restore caps at `Cart.MAX_QUANTITY` (20), skips new books beyond `Cart.MAX_LINES` (50); once per order.
- Lines ≤ 120 characters; `ChangeLog` for mutations; `@NullMarked` packages (no new package).

## Review Focus

- Customer edits the cart while a rejection restores it: the listener must not fail (row lock), the customer gets
  409. Covered by design; test by reasoning in review (hard to make deterministic).
- Order with the same book twice? Not possible (cart lines are unique per book).
- `Stock` mocked in ordering module tests must default to "plenty", or every existing checkout test breaks.
- Not-for-sale beats out-of-stock: a cart with both gets the not-for-sale message. Task 2 test.
- Redelivered `StockReservationFailed` restores once. Task 3 test.

---

### Task 1: Inventory `Stock` API

**Files:** Create `inventory/Stock.java`; modify `inventory/internal/InventoryService.java` (implements `Stock`),
`inventory/package-info.java` (Javadoc mentions `Stock`); test `inventory/InventoryModuleTests.java`.

- [ ] Failing test `reportsStockLevels`: restock book A by 3 (via `InventoryService.restock`, catalog mocked as in
  the existing tests); `availableOf(List.of(A, B))` equals `{A=3, B=0}`.
- [ ] Implement with `repository.findAllById(ids)`, filling missing ids with 0. Run
  `./gradlew test --tests '*InventoryModuleTests' --tests '*ModularityTests'` → PASS. Commit
  `Inventory: Stock read API for other modules`.

### Task 2: Stock check at checkout

**Files:** Create `ordering/internal/OutOfStockException.java`; modify `ordering/internal/CartService.java`
(inject `Stock`), tests `ordering/internal/OrderingModuleTests.java` (`@MockitoBean Stock`, default: 100 of each),
`ordering/OrderingIntegrationTest.java` (rewrite `missingStockRejectsTheOrderWithoutTakingAnything` →
`checkoutRefusesShortStockAndKeepsTheCart`), `ordering/internal/CheckoutDeleteRaceTest.java` unaffected.

- [ ] Module tests: `checkoutRefusesShortStock` (DUNE 2 wanted, stock {DUNE=1, FOUNDATION=5}, FOUNDATION 1 wanted →
  `OutOfStockException` message `Not enough stock: Dune (1 left). Change the quantities and check out again.`;
  cart still has both lines; no order for the customer); `notForSaleBeatsOutOfStock`.
- [ ] HTTP test: book with 1 in stock, cart 2 → 409 problem detail as above with the book's title, `GET /api/cart`
  still quantity 2, `GET /api/orders` has no order, stock still 1.
- [ ] Implement; run `./gradlew test --tests '*OrderingModuleTests' --tests '*OrderingIntegrationTest'` → PASS.
  Commit `Ordering: refuse checkout when stock is short`.

### Task 3: Restore the cart on rejection

**Files:** modify `ordering/internal/Cart.java` (`add`), `CartRepository.java` (`findAndLockById`),
`CartService.java` (`restore(CustomerOrder)`), `OrderProcess.java` (call it in both rejections); tests
`OrderingModuleTests`, `OrderingIntegrationTest.declinedPaymentRejectsTheOrderAndPutsStockBack`.

- [ ] Module tests: `stockRejectionPutsLinesBackIntoTheCart` (order DUNE 2 + FOUNDATION 1; meanwhile customer adds
  DUNE 19; publish `StockReservationFailed` → cart DUNE 20 (capped), FOUNDATION 1); `paymentRejectionPutsLinesBack`;
  `redeliveredRejectionRestoresOnce` (publish the failure twice → FOUNDATION 1, not 2).
- [ ] HTTP: declined payment → `GET /api/cart` shows the order's book with quantity 2 again.
- [ ] Implement; run the ordering tests → PASS. Commit `Ordering: put rejected orders back into the cart`.

### Task 4: Docs, review, build

- [ ] Update `docs/design/orders.md`, README (Orders section, checkout API row), CLAUDE.md if needed; spec status →
  implemented.
- [ ] Whole-branch review (one reviewer, most capable model); fix findings.
- [ ] `./gradlew build` green; commit; merge to main on the user's word (already given: "build it" — merge only
  when asked).
