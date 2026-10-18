# Customer addresses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Customers save one preferred shipping and one preferred invoice address, can override either at checkout, and every order keeps its own copy of both.

**Architecture:** A new Modulith module `customer` owns the `Address` value and the saved addresses and exposes `CustomerAddresses` to `ordering`. `ordering` resolves both addresses at checkout (request, else saved, else 400) and stores them as columns on `customer_order`. `security` and `Account` are untouched apart from the new URL rules.

**Tech Stack:** Spring Boot 4.1 / Hibernate 7, Spring Modulith, Flyway, JUnit 5 + Testcontainers, `RestTestClient`.

**Spec:** `docs/design/customer-addresses.md`

## Global Constraints

- Module packages: `cz.codingmonkey.bookstore.customer` (public API) and `.customer.internal`; each has a `package-info.java` with `@NullMarked`, the root one also `@ApplicationModule(displayName = "Customers")`.
- Migrations: `V27__create_customer_address.sql`, `V28__add_order_addresses.sql`. Never edit an existing migration.
- `Address` fields, all required and stripped: `name` (max 100), `street` (max 200), `city` (max 100), `postalCode` (max 20), `country` (two letters, ISO 3166-1 alpha-2, normalized to upper case). The spec left the lengths as a range; these are the values.
- Endpoints (role `CUSTOMER` only): `GET /api/me/addresses`, `PUT` and `DELETE` on `/api/me/addresses/shipping` and `/api/me/addresses/invoice`. New URLs must be listed in `SecurityConfiguration` (unlisted paths are denied).
- Mutations are logged only through `shared.ChangeLog`, naming the customer and the address type, never the address (personal data).
- Checkout error messages (400, `@ResponseStatus(BAD_REQUEST)`): `Shipping address is required: send one or save one`, `Invoice address is required: send one or save one`, and when both are missing `Shipping and invoice addresses are required: send them or save them`.
- New API code follows the OpenAPI conventions in `CLAUDE.md`: Javadoc, `@Tag`, an `@ApiResponse` for every specific status including the success one.
- Every Spring test imports `TestcontainersConfiguration`. Run tests with `./gradlew test --tests '<class>'`; Docker must be running.

## Review Focus

- A customer signing in with a different letter case of their username (`Bea` vs `bea`) sees the same addresses (owned by Task 2).
- Two first-time saves at once (shipping and invoice in parallel) must both succeed, not fail on the row's primary key (Task 1).
- Request body with one blank or missing field of an address, or a one-letter country, gives 400, never 500 or a half-stored address (Tasks 1 and 3).
- `{}`, an empty body, and a body with only `shipping` all work at checkout; the missing one falls back to the saved one (Task 3).
- Orders placed before this change stay readable: their `shipping` and `invoice` are `null` in the API (Task 3).
- Staff and admins listing all orders do not receive customers' addresses (Task 3; see the open point in the plan's hand-off).

---

### Task 1: Module `customer` — `Address`, persistence, service

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/customer/{Address,CustomerAddresses,package-info}.java`
- Create: `src/main/java/cz/codingmonkey/bookstore/customer/internal/{AddressService,SavedAddresses,SavedAddressesRepository,package-info}.java`
- Create: `src/main/resources/db/migration/V27__create_customer_address.sql`
- Test: `src/test/java/cz/codingmonkey/bookstore/customer/AddressTest.java`, `src/test/java/cz/codingmonkey/bookstore/customer/internal/CustomerModuleTests.java`
- Create (fixture): `src/test/java/cz/codingmonkey/bookstore/customer/TestAddresses.java`

**Interfaces:**
- Produces:
  - `public record Address(String name, String street, String city, String postalCode, String country)`, `@Embeddable`, with `jakarta.validation` constraints (`@NotBlank`, `@Size`, `@Pattern` on the country) and a null-tolerant compact constructor that strips all fields and upper-cases `country` (`Locale.ROOT`), so missing fields reach bean validation as 400 instead of throwing.
  - `public interface CustomerAddresses { Optional<Address> shipping(String username); Optional<Address> invoice(String username); }`
  - Package-private `AddressService implements CustomerAddresses` with `SavedAddressesView get(String customer)` (record `SavedAddressesView(@Nullable Address shipping, @Nullable Address invoice)`), `Address saveShipping(String customer, Address address)`, `Address saveInvoice(String customer, Address address)`, `void deleteShipping(String customer)`, `void deleteInvoice(String customer)`.
  - Test fixture `TestAddresses`: `static final Address SHIPPING`, `INVOICE` (two different valid addresses) and `static String json(Address a)`.

- [ ] **Step 1: Write the failing `AddressTest`** (plain unit test with `Validation.buildDefaultValidatorFactory()`): `stripsAndUpperCasesCountry` (`new Address(" Bea ", " Main 1 ", " Brno ", " 602 00 ", "cz")` equals the stripped values with country `"CZ"`); `rejectsBlankAndMissingFields` (each of the five fields blank or `null` gives one violation on that field, no exception from the constructor); `rejectsTooLongAndBadCountry` (name of 101 chars, street of 201, city of 101, postal code of 21, country `"C"`, `"CZE"`, `"C1"`).
- [ ] **Step 2: Run** `./gradlew test --tests '*AddressTest'`. Expected: FAIL (class missing).
- [ ] **Step 3: Implement `Address`, `CustomerAddresses`, `package-info.java`** as specified above. Mark `Address` `@Embeddable`; leave the embeddable's columns nullable so one record can be embedded twice.
- [ ] **Step 4: Run** `AddressTest`. Expected: PASS.
- [ ] **Step 5: Write the failing `CustomerModuleTests`** (`@ApplicationModuleTest` + `@Import(TestcontainersConfiguration.class)`, autowire `AddressService`, use a random username per test): `startsEmpty` (`get` gives both `null`, `shipping`/`invoice` return `Optional.empty()`); `shippingAndInvoiceAreKeptApart` (save shipping, then invoice: `get` returns both, equal to what was saved); `saveReplaces` (second `saveShipping` replaces the first, invoice untouched); `deleteRemovesOnlyThatOne` (delete shipping leaves invoice; deleting when nothing is saved doesn't throw); `customersDontSeeEachOther`; `parallelFirstSavesBothSucceed` (two threads, `saveShipping` and `saveInvoice` for a new customer released together via a `CountDownLatch`; afterwards both addresses are stored, 20 repetitions with fresh usernames); `halfAnAddressCannotBeStored` (`JdbcTemplate` `INSERT` with only `shipping_name` set fails with `DataIntegrityViolationException`).
- [ ] **Step 6: Run** `./gradlew test --tests '*CustomerModuleTests'`. Expected: FAIL (no module).
- [ ] **Step 7: Create `V27__create_customer_address.sql`:** table `customer_address(customer VARCHAR(100) PRIMARY KEY, shipping_name VARCHAR(100), shipping_street VARCHAR(200), shipping_city VARCHAR(100), shipping_postal_code VARCHAR(20), shipping_country CHAR(2), invoice_*` same columns`)`, all address columns nullable, plus `CHECK (num_nonnulls(shipping_name, shipping_street, shipping_city, shipping_postal_code, shipping_country) IN (0, 5))` and the same for `invoice_*`. Start the file with the comment style of `V25` (owned by the customer module, customers referenced by username).
- [ ] **Step 8: Implement `SavedAddresses`** (`@Entity @Table(name = "customer_address")`, `@Id String customer`, two nullable `@Embedded Address` fields `shipping` and `invoice` mapped to the `shipping_*` / `invoice_*` columns; use Hibernate 7's `@EmbeddedColumnNaming("shipping_%s")` and `("invoice_%s")` if it exists, otherwise `@AttributeOverrides`), `SavedAddressesRepository extends Repository`/`JpaRepository<SavedAddresses, String>` with `@Lock(PESSIMISTIC_WRITE) Optional<SavedAddresses> findAndLockByCustomer(String)` (copy the shape of `CartRepository.findAndLockByCustomer`) and a native `@Modifying @Query("INSERT INTO customer_address (customer) VALUES (:customer) ON CONFLICT DO NOTHING") void insertIfAbsent(String customer)`. Hibernate returns `null` for an embedded group whose columns are all null, which is how "no address" maps; confirm that in `startsEmpty`.
- [ ] **Step 9: Implement `AddressService`** (`@Service @Transactional(readOnly = true)`, class-level `ChangeLog`): the save methods call `insertIfAbsent`, then `findAndLockByCustomer`, set the group, and log `Customer {} saved a shipping address` / `... invoice address`; the delete methods use the same lock-then-set-null path; the `CustomerAddresses` methods read without locking.
- [ ] **Step 10: Run** `AddressTest` and `CustomerModuleTests`. Expected: PASS. Then `./gradlew test --tests cz.codingmonkey.bookstore.ModularityTests`. Expected: PASS.
- [ ] **Step 11: Commit** — `git add` the files above; message `Add customer module with saved shipping and invoice addresses`.

---

### Task 2: Address endpoints and security rules

**Files:**
- Create: `src/main/java/cz/codingmonkey/bookstore/customer/internal/AddressController.java`
- Modify: `src/main/java/cz/codingmonkey/bookstore/security/SecurityConfiguration.java` (the "Customers:" block, next to the recommendations/rating rules)
- Test: `src/test/java/cz/codingmonkey/bookstore/customer/AddressIntegrationTest.java` (new), `src/test/java/cz/codingmonkey/bookstore/security/SecurityIntegrationTest.java` (add cases), `src/test/java/cz/codingmonkey/bookstore/OpenApiIntegrationTest.java` (add paths)

**Interfaces:**
- Consumes: `AddressService` and `SavedAddressesView` from Task 1, `TestAddresses`.
- Produces: the five endpoints; `GET` answers `{shipping, invoice}` (either `null`), `PUT` answers the saved `Address`, `DELETE` answers 204.

- [ ] **Step 1: Write the failing `AddressIntegrationTest`** (`@SpringBootTest(RANDOM_PORT)`, `@AutoConfigureRestTestClient`; sign up customers like `OrderingIntegrationTest.signUp`): `newCustomerHasNoAddresses` (GET gives `shipping` and `invoice` both null); `putThenGet` (PUT shipping returns the address, GET shows it and `invoice` still null); `putReplaces`; `deleteIsIdempotent` (DELETE twice gives 204 both times, GET shows null); `invalidBodyIs400` (blank `street`, missing `city`, country `"CZE"` each give 400 with `application/problem+json`; GET afterwards still shows nothing stored); `countryIsStoredUpperCase`; `customersAreIsolated` (second customer's GET is empty); `usernameCaseDoesNotMatter` (sign in as the same account with the username in upper case and GET returns the address saved before).
- [ ] **Step 2: Add failing cases to `SecurityIntegrationTest`:** for all five endpoints anonymous gets 401, `STAFF` and `ADMIN` get 403 (follow the existing rating cases in that file).
- [ ] **Step 3: Run** both classes. Expected: FAIL (404/403 mismatches because the paths are denied).
- [ ] **Step 4: Implement `AddressController`** with `@RequestMapping("/api/me/addresses")`, `@Tag(name = "Addresses", description = ...)`, `Principal` for the username, `@Valid @RequestBody Address`, `@ResponseStatus(NO_CONTENT)` on the deletes, Javadoc and `@ApiResponse` (200/400 on PUT, 204 on DELETE) as in `RatingController`. Add the rules to `SecurityConfiguration` with a comment line `// Customers: their saved shipping and invoice addresses.` using `.requestMatchers("/api/me/addresses", "/api/me/addresses/**").hasRole(Role.CUSTOMER.name())`.
- [ ] **Step 5: Add the new paths to `OpenApiIntegrationTest`** where it asserts other paths exist (`/api/me/addresses` GET, `/api/me/addresses/shipping` PUT with a `200`, DELETE with a `204`).
- [ ] **Step 6: Run** `AddressIntegrationTest`, `SecurityIntegrationTest`, `OpenApiIntegrationTest`. Expected: PASS.
- [ ] **Step 7: Commit** — `Add customer address endpoints for customers only`.

---

### Task 3: Orders carry addresses; checkout resolves them

**Files:**
- Create: `src/main/resources/db/migration/V28__add_order_addresses.sql`
- Create: `src/main/java/cz/codingmonkey/bookstore/ordering/internal/{CheckoutRequest,MissingAddressException}.java`
- Modify: `ordering/internal/CustomerOrder.java`, `OrderingResponses.java` (`OrderView`), `CartService.java` (`checkout`), `OrderService.java` (`findAll`), `OrderingController.java` (`checkout`)
- Test: `ordering/internal/CustomerOrderTest.java`, `ordering/internal/OrderingModuleTests.java`, `ordering/internal/CheckoutDeleteRaceTest.java`, `ordering/OrderingIntegrationTest.java`, `OpenApiIntegrationTest.java`; new `ordering/CheckoutAddressesIntegrationTest.java`

**Interfaces:**
- Consumes: `Address`, `CustomerAddresses`, `TestAddresses` (Tasks 1–2).
- Produces:
  - `record CheckoutRequest(@Nullable @Valid Address shipping, @Nullable @Valid Address invoice)` with `static final CheckoutRequest NONE`, in `ordering.internal`, documented for OpenAPI.
  - `OrderView` gains `@Nullable Address shipping, @Nullable Address invoice` after `createdAt`; `OrderView.from(order)` fills them, new `OrderView.withoutAddresses(order)` leaves them `null`.
  - `CustomerOrder(String customer, List<OrderLine> lines, Currency currency, Address shipping, Address invoice)`; getters `Address getShipping()`, `getInvoice()` return `@Nullable` (old orders).
  - `CartService.checkout(String customer, CheckoutRequest request)`; `MissingAddressException` (package-private, `@ResponseStatus(BAD_REQUEST)`).

- [ ] **Step 1: Write the failing tests.**
  - `CustomerOrderTest`: update the fixture to the new constructor and add `keepsItsOwnAddresses` (getters return the two addresses given).
  - `OrderingModuleTests`: add `@MockitoBean CustomerAddresses addresses` (the customer module doesn't run here), defaulting in the `@BeforeEach` to `TestAddresses.SHIPPING` / `INVOICE` for any username; call `carts.checkout(customer, CheckoutRequest.NONE)` everywhere; add `checkoutUsesSavedAddresses`, `requestAddressesOverrideSavedOnes` (override only invoice: the order has saved shipping and the request invoice), `missingShippingIsRefusedAndNothingIsReserved` (saved shipping empty, no request: `MissingAddressException` with the shipping message, cart still filled, no `StockReservationRequested` published), `missingInvoiceIsRefused`, `bothMissingNamesBoth`, `emptyCartIsStillConflictWhenAddressesAreMissing` (`EmptyCartException` wins).
  - `CheckoutDeleteRaceTest`: pass `new CheckoutRequest(TestAddresses.SHIPPING, TestAddresses.INVOICE)`.
  - `CheckoutAddressesIntegrationTest` (HTTP, all modules): `savedAddressesAreCopiedToTheOrder`; `editingSavedAddressesDoesNotChangeTheOrder` (check out, `PUT` a new shipping address, `GET /api/orders/{id}` still shows the old one); `bodyMayHaveOnlyOneAddress` (`{"invoice": ...}` with a saved shipping works; `{}` and no body work with both saved); `missingAddressIs400WithMessageAndKeepsCartAndStock` (no addresses anywhere: 400, `detail` equals the shipping+invoice message, cart unchanged, stock unchanged); `invalidAddressInBodyIs400`; `orderAddressesVisibleToOwnerNotInStaffList` (`GET /api/orders/all` as staff has `shipping` and `invoice` null); `oldOrdersHaveNullAddresses` (insert a legacy-shaped `customer_order` row with `JdbcTemplate` without address columns, `GET` shows both `null`).
  - `OrderingIntegrationTest`: in `setUp` the new customer saves addresses with `PUT` so existing scenarios keep checking out.
  - `OpenApiIntegrationTest`: checkout still has `202`, and now a request body schema.
- [ ] **Step 2: Run** `./gradlew test --tests 'cz.codingmonkey.bookstore.ordering.*'`. Expected: FAIL (compilation).
- [ ] **Step 3: Create `V28__add_order_addresses.sql`:** `ALTER TABLE customer_order ADD COLUMN` for `shipping_*` and `invoice_*` with the same types as V27, all nullable (existing orders have none), and the same two `num_nonnulls(...) IN (0, 5)` checks.
- [ ] **Step 4: Implement the code** per the Interfaces block. In `CustomerOrder` embed the two groups exactly as `SavedAddresses` does (`@Embedded`, same column-naming approach). In `CartService.checkout`: right after the empty-cart check, resolve each address as `request value, else addresses.shipping(customer)/invoice(customer)`; if either is absent throw `MissingAddressException` (messages in Global Constraints) before any book is locked or stock checked; inject `CustomerAddresses` into the constructor. In `OrderService.findAll` map with `OrderView::withoutAddresses`. In `OrderingController.checkout` take `@RequestBody(required = false) @Valid @Nullable CheckoutRequest`, pass `NONE` when absent, document both addresses and the new 400 in Javadoc and `@ApiResponse(responseCode = "400", ...)`.
- [ ] **Step 5: Run** `./gradlew test --tests 'cz.codingmonkey.bookstore.ordering.*' --tests '*OpenApiIntegrationTest'`. Expected: PASS.
- [ ] **Step 6: Run** `./gradlew test` (all). Expected: PASS; fix any other test that checks out without addresses by saving addresses first or passing them in the request.
- [ ] **Step 7: Commit** — `Orders keep a copy of shipping and invoice address; checkout requires both`.

---

### Task 4: Docs

**Files:**
- Modify: `README.md` (module table; a new "Customer addresses" section next to "Ratings"; Orders section checkout text; the dependency diagram `ordering ──CustomerAddresses──▶ customer`; the API table rows for the five endpoints and the checkout row), `docs/design/orders.md` (checkout: addresses required and copied), `CLAUDE.md` (add `customer` to the module list and say addresses are only for customers), `docs/design/customer-addresses.md` (Status: implemented with the date, the pinned field lengths, the staff-list decision).

- [ ] **Step 1: Make the edits** above, in the existing style of each file.
- [ ] **Step 2: Run** `./gradlew build`. Expected: BUILD SUCCESSFUL (all tests, including `ModularityTests`).
- [ ] **Step 3: Commit** — `Document customer addresses`.

---

## Self-review notes

- Spec coverage: §1 module → Task 1; §2 table → Task 1 (V27); §3 API and ChangeLog → Task 2 and Task 1 step 9; §4 checkout → Task 3; §5 snapshot (V28, view, old orders) → Task 3; §6 boundaries and docs → Tasks 1 and 4; §7 testing → tests in every task.
- Where this plan decides something the spec left open: field lengths; country normalized to upper case instead of rejecting lower case; address check placed after the empty-cart check; `/api/orders/all` omits addresses.
