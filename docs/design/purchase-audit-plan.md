# Purchase Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every step of a purchase appears in the audit log under the order id, with the customer as actor.

**Architecture:** The ordering module publishes one `OrderProgressedEvent` per step it observes (it already reacts
to every inventory and payment result). Audit stores each under a new `order_id` column. Inventory gains a
`StockReleased` event; payment announces a refund only once. Inventory and payment stay unaware of orders.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Modulith 2.1 (`@ApplicationModuleListener`, `Scenario`,
`PublishedEvents`), JPA/Hibernate 7, Flyway, Testcontainers PostgreSQL, Gradle.

**Spec:** `docs/design/purchase-audit.md`

## Global Constraints

- Migration file: `src/main/resources/db/migration/V21__add_audit_order.sql` (latest today is V20).
- Lines ≤ 120 characters; typed ids (records + `shared.LongIdType`), no bare `Long` ids.
- Log data changes via `shared.ChangeLog` with ids only (no names/emails).
- Step names exactly: `PLACED, STOCK_RESERVED, STOCK_MISSING, PAYMENT_CHARGED, PAYMENT_DECLINED, CONFIRMED,
  REJECTED, CANCELLED, STOCK_RELEASED, PAYMENT_REFUNDED`; audit action = `"ORDER_" + step`.
- No git repository: instead of commits, each task ends with the task's tests plus `./gradlew test` green.
- Test command: `./gradlew test --tests '<pattern>'`; result XML in `build/test-results/test/`.

## Review Focus

- `OrderProgressedEvent` must survive the event registry's JSON round trip (republish after restart): test in Task 3.
- Two `RefundRequested` at once must still yield exactly one `PaymentRefunded` (row lock already exists): Task 2.
- Out-of-stock orders never request a release, so they must show no `ORDER_STOCK_RELEASED`: Task 5 sequence.
- Existing book/author audit rows must still satisfy the new check constraint: Task 4 migration test.
- `/api/audit?orderId=1&bookId=1` must be 400, not an empty page: Task 4.

---

### Task 1: Inventory announces `StockReleased`

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/inventory/StockReleased.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/inventory/internal/StockReservationService.java` (`on(StockReleaseRequested)`, ~line 94)
- Modify: `src/main/java/cz/codingmonkey/bookstore/inventory/package-info.java` (mention the event)
- Test: `src/test/java/cz/codingmonkey/bookstore/inventory/internal/StockReservationModuleTests.java`

**Interfaces:**
- Produces: `public record StockReleased(ReservationId reservationId)` (`@DomainEvent`), published once per
  reservation, right after the stock is put back (also when some lines were skipped because the book is gone).

- [ ] **Step 1: Failing test** `releaseIsAnnouncedOnce(PublishedEvents, Scenario)`: reserve `dune` ×2; stimulate two
  `StockReleaseRequested` for the same id in one `scenario.stimulate(...)`; wait until quantity is 3; then
  `await().during(700ms)`: `assertThat(events.ofType(StockReleased.class).matching(r -> r.reservationId().equals(id))).hasSize(1)`.
- [ ] **Step 2:** Run `./gradlew test --tests '*StockReservationModuleTests'` → FAIL (class missing).
- [ ] **Step 3:** Add the record; publish it at the end of the release branch (after the restock loop, before the
  `changes.info`).
- [ ] **Step 4:** Run the same command → PASS (8 tests); then `./gradlew test` green.

### Task 2: Payment announces a refund only once

**Files:**
- Modify: `src/main/java/cz/codingmonkey/bookstore/payment/internal/PaymentService.java` (`on(RefundRequested)`)
- Test: `src/test/java/cz/codingmonkey/bookstore/payment/internal/PaymentModuleTests.java`

**Interfaces:** `PaymentRefunded(PaymentReference reference)` unchanged; now published only when
`Payment.refund()` returned `true`.

- [ ] **Step 1: Failing test** `refundIsAnnouncedOnce(PublishedEvents, Scenario)`: charge; refund once and wait for
  `PaymentRefunded`; then stimulate a second `RefundRequested`; `await().during(700ms)` that
  `events.ofType(PaymentRefunded.class).matching(ref)` has size 1. Also keep `refundsOnce` (concurrent pair).
- [ ] **Step 2:** Run `./gradlew test --tests '*PaymentModuleTests'` → FAIL (2 events).
- [ ] **Step 3:** Move `publishEvent(new PaymentRefunded(...))` inside `if (payment.refund())`; drop the
  `status == REFUNDED` publish.
- [ ] **Step 4:** Run → PASS (6 tests); `./gradlew test` green.

### Task 3: Ordering publishes `OrderProgressedEvent`

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/ordering/OrderStep.java` (public enum, values from Global Constraints)
- Create: `src/main/java/cz/codingmonkey/bookstore/ordering/OrderProgressedEvent.java`
- Move: `ordering/internal/OrderIdType.java` → `ordering/OrderIdType.java` (public class, public no-arg ctor,
  javadoc like `BookIdType`); update import in `ordering/internal/CustomerOrder.java`
- Modify: `ordering/internal/CartService.java` (`checkout`), `ordering/internal/OrderService.java` (`cancel`),
  `ordering/internal/OrderProcess.java` (four existing listeners + two new ones)
- Test: `src/test/java/cz/codingmonkey/bookstore/ordering/internal/OrderingModuleTests.java`

**Interfaces:**
- Produces: `public record OrderProgressedEvent(OrderId orderId, OrderStep step, String detail, String actor)`
  (`@DomainEvent`); `public class OrderIdType extends LongIdType<OrderId>`.
- Consumes: `StockReleased` (Task 1), `PaymentRefunded` (Task 2).
- Publishing (actor = order's customer always; detail strings exactly as below; `%s` of `Money` prints
  `38.97 EUR`):

| Where | Step(s) | detail |
|---|---|---|
| `CartService.checkout` after save | `PLACED` | `"%d copies, %s".formatted(sumOfQuantities, total)` |
| `OrderProcess.on(StockReserved)` inside `if (order.stockReserved())` | `STOCK_RESERVED` | `"Stock reserved"` |
| `on(StockReservationFailed)` inside its `if` | `STOCK_MISSING`, then `REJECTED` | both: `"Not enough stock: " + titles` |
| `on(PaymentSucceeded)` inside `if (order.paid())` | `PAYMENT_CHARGED`, then `CONFIRMED` | `"Charged " + total`, then `"Confirmed"` |
| `on(PaymentFailed)` inside its `if` | `PAYMENT_DECLINED`, then `REJECTED` | both: `event.reason()` |
| `OrderService.cancel` | `CANCELLED` | `"Cancelled by the customer"` |
| new `on(StockReleased)` (order exists) | `STOCK_RELEASED` | `"Stock put back"` |
| new `on(PaymentRefunded)` (order exists) | `PAYMENT_REFUNDED` | `"Refunded " + total` |

- [ ] **Step 1: Failing tests** in `OrderingModuleTests` (helper `steps(events, order)` = list of
  `OrderProgressedEvent::step` for that order, in publication order):
  - `checkoutPublishesPlaced`: step list `[PLACED]`, detail `"3 copies, 41.97 EUR"` for DUNE×2 + FOUNDATION×1, actor = customer.
  - `happyPathPublishesEachStep`: after StockReserved + PaymentSucceeded → `[PLACED, STOCK_RESERVED, PAYMENT_CHARGED, CONFIRMED]`; detail of `PAYMENT_CHARGED` = `"Charged 14.99 EUR"`.
  - `missingStockPublishesMissingThenRejected`: → `[PLACED, STOCK_MISSING, REJECTED]`, detail `"Not enough stock: Dune"`.
  - `declinedPaymentPublishesDeclinedThenRejected`: → `[..., STOCK_RESERVED, PAYMENT_DECLINED, REJECTED]`, detail `"Declined"`.
  - `cancelAndUndoArePublished`: confirmed order, cancel, then publish `StockReleased` and `PaymentRefunded` →
    ends with `[CANCELLED, STOCK_RELEASED, PAYMENT_REFUNDED]`; `"Refunded 14.99 EUR"`.
  - extend `repeatedAndLateResultsAreIgnored`: during 700 ms exactly one `STOCK_RESERVED` for the order.
  - `progressEventsSurviveTheEventRegistry`: `EventSerializer` round trip of an `OrderProgressedEvent` equals the original.
- [ ] **Step 2:** Run `./gradlew test --tests '*OrderingModuleTests'` → FAIL (types missing).
- [ ] **Step 3:** Add enum, event, public `OrderIdType`; publish per table via a private
  `progress(CustomerOrder order, OrderStep step, String detail)` helper in `OrderProcess` (and inline in the two
  services). New listeners use the existing `withOrder(...)`.
- [ ] **Step 4:** Run → PASS; `./gradlew test` green (incl. `ModularityTests`).

### Task 4: Audit stores order steps

**Files:**
- Create: `src/main/resources/db/migration/V21__add_audit_order.sql`
- Modify: `audit/AuditEntry.java`, `audit/AuditListener.java`, `audit/AuditEntryRepository.java`,
  `audit/AuditController.java`
- Test: `src/test/java/cz/codingmonkey/bookstore/audit/AuditModuleTests.java`,
  `src/test/java/cz/codingmonkey/bookstore/audit/AuditOrderMigrationTest.java` (new)

**Interfaces:**
- Consumes: `OrderProgressedEvent`, `OrderStep`, `OrderId`, `OrderIdType` (Task 3).
- Produces: `static AuditEntry forOrder(Action action, OrderId orderId, String actor, String description)`;
  `OrderId getOrderId()`; repository `List<AuditEntry> findByOrderIdOrderByOccurredAtDesc(OrderId)` and
  `Page<AuditEntry> findByOrderId(OrderId, Pageable)`; `AuditEntryResponse` gains `OrderId orderId` after `authorId`.

Migration body:
```sql
ALTER TABLE audit_entry ADD COLUMN order_id BIGINT;
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_one_subject;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_one_subject CHECK (num_nonnulls(book_id, author_id, order_id) = 1);
CREATE INDEX audit_entry_order_idx ON audit_entry (order_id);
```

- [ ] **Step 1: Failing tests**
  - `AuditModuleTests.recordsOrderSteps`: publish `OrderProgressedEvent(new OrderId(48), PAYMENT_DECLINED, "Declined", "rita")` → one entry, action `ORDER_PAYMENT_DECLINED`, orderId 48, actor `rita`, description `"Declined"`, bookId null.
  - extend `databaseRequiresExactlyOneSubject` with `(book_id, order_id)` both set → `DataIntegrityViolationException`.
  - `AuditOrderMigrationTest` (pattern of `PriceHistoryMigrationTest`): migrate to 20, insert one book entry, migrate to 21 → succeeds, row unchanged.
- [ ] **Step 2:** Run `./gradlew test --tests 'cz.codingmonkey.bookstore.audit.*'` → FAIL.
- [ ] **Step 3:** Migration; `AuditEntry` field `@Type(OrderIdType.class) private OrderId orderId` + factory + enum
  values `ORDER_PLACED … ORDER_PAYMENT_REFUNDED`; `AuditListener.on(OrderProgressedEvent)` →
  `record(AuditEntry.forOrder(Action.valueOf("ORDER_" + e.step()), e.orderId(), e.actor(), e.detail()))`;
  `record(...)` log line prints `"order"` subject; controller: `@RequestParam(required = false) OrderId orderId`,
  more than one of the three filters → 400 `"Filter by one of bookId, authorId or orderId"`.
- [ ] **Step 4:** Run → PASS; `./gradlew test` green.

### Task 5: End to end + docs

**Files:**
- Modify: `src/test/java/cz/codingmonkey/bookstore/ordering/OrderingIntegrationTest.java`
- Modify: `README.md` (audit section + API row), `docs/design/purchase-audit.md` (status: implemented)

- [ ] **Step 1: Tests** (helper `auditActions(order)` = `GET /api/audit?orderId={id}&size=50` as staff/admin, list of
  `action` oldest first, waits with Awaitility until the expected last action appears):
  - in `ordersAreConfirmedAndCanBeCancelled`: `[ORDER_PLACED, ORDER_STOCK_RESERVED, ORDER_PAYMENT_CHARGED, ORDER_CONFIRMED, ORDER_CANCELLED, ORDER_STOCK_RELEASED, ORDER_PAYMENT_REFUNDED]`, every `actor` = the customer's username (order of the last two may vary → compare first five exactly, last two as a set).
  - in `declinedPaymentRejectsTheOrderAndPutsStockBack`: `[ORDER_PLACED, ORDER_STOCK_RESERVED, ORDER_PAYMENT_DECLINED, ORDER_REJECTED, ORDER_STOCK_RELEASED]`.
  - in `missingStockRejectsTheOrderWithoutTakingAnything`: exactly `[ORDER_PLACED, ORDER_STOCK_MISSING, ORDER_REJECTED]` (during 700 ms, no `ORDER_STOCK_RELEASED`).
  - `auditFiltersAreExclusive`: `/api/audit?orderId=1&bookId=1` as admin → 400.
- [ ] **Step 2:** Run `./gradlew test --tests '*OrderingIntegrationTest'` → PASS (code from Tasks 1–4).
- [ ] **Step 3:** README: audit lists order steps, `?orderId=` filter; spec status → implemented.
- [ ] **Step 4:** `./gradlew test` → all green (248 + new tests), `ModularityTests` shows Audit → Ordering.
