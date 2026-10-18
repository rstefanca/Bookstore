# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Bookstore: a Spring Boot 4 REST API on PostgreSQL (with pgvector), structured as Spring Modulith modules and
tested with Testcontainers. Java 25 via Gradle toolchain (Temurin is auto-downloaded if missing). `README.md` is
the detailed reference for behavior, the API table and per-module rules; keep it in sync when behavior changes.

## Commands

All tests need a running Docker daemon (Testcontainers starts `pgvector/pgvector:pg17`).

```bash
./gradlew build                     # compile + all tests
./gradlew test                      # all tests
./gradlew test --tests 'cz.codingmonkey.bookstore.storefront.StorefrontIntegrationTest'
./gradlew test --tests '*OrderingModuleTests.someMethodName'
./gradlew test --tests cz.codingmonkey.bookstore.ModularityTests   # module boundary check
./gradlew bootTestRun               # run locally on a throwaway Postgres container with the `demo` profile
```

There is no separate linter/formatter configured. `bootRun` (`BookstoreApplication`) instead needs your own
Postgres at `localhost:5432/bookstore` (user/pass `bookstore`) with pgvector 0.8+ available. On first start the
recommendation module downloads the ONNX embedding model (internet needed unless
`bookstore.recommendation.model-uri`/`tokenizer-uri` point at local files).

## Architecture

### Spring Modulith modules

Each direct subpackage of `cz.codingmonkey.bookstore` is a module (`package-info.java` carries
`@ApplicationModule`). Types in a module's root package are its public API; everything in `<module>.internal` is
private. `ModularityTests` enforces this and rejects cycles, so cross-module access must go through public types
or events, never another module's `internal` package or tables.

Modules: `bookcatalog` (books, authors, genres), `inventory` (stock + reservations), `pricing` (`Money`, list
prices, price history), `storefront` (customer reads composing catalog + prices + ratings), `ordering` (cart, orders,
`OrderProcess`), `payment` (fake provider), `audit`, `recommendation` (pgvector embeddings), `rating` (customer
star ratings), `customer` (saved shipping/invoice addresses; customers only, staff and admins hold none), `security` (accounts, `SecurityConfiguration`), `shared` (technical building blocks only, no domain
logic).

Dependencies only point one way. Patterns used to keep it that way:
- **SPI instead of calling back**: `bookcatalog.BookDeletionPolicy` is implemented by inventory/ordering so catalog
  can veto deletes without depending on them. Restock/set-price/rate/checkout lock the book row `FOR KEY SHARE`
  (`BookCatalog.existsAndLockAgainstDeletion`; blocks the delete, not edits of the book); delete takes
  `FOR UPDATE` before consulting policies (`RestockDeleteRaceTest`). `security.AccountDeletionPolicy` works the same
  way for accounts: ordering/rating/customer/audit implement it (`holdsRecords`, `removePersonalData`,
  `anonymize`), so `AccountService.delete` hard-deletes an account nobody holds records for and soft-deletes
  (anonymizes) the rest. Those modules depend on `security`'s public API, never the reverse.
- **Conditions and orders handed to the catalog**: `bookcatalog.BookCondition` and `BookOrder` (set on a `BookSearch`
  with `matching`/`ordered`) let pricing and rating give the catalog a filter or sort for its own book query, so
  storefront can offer price ranges, `minRating` and `orderBy=rating` with correct paging and totals, and catalog
  still doesn't know those modules.
- **Request → result events**: `inventory` and `payment` expose `*Requested` events and publish result events
  (`StockReserved`/`StockReservationFailed`, `PaymentSucceeded`/`PaymentFailed`, ...). They don't know about
  orders; `ordering` depends on them, not the reverse. See `docs/design/orders.md` for the order state machine
  and compensation (release stock / refund) flow.
- **Composition at the edge**: `storefront` combines `BookCatalog`, `Prices`, `Ratings` and `Recommendations` (ids +
  similarity only, filtered to books for sale); catalog, pricing, rating and recommendation don't know each other.

### Events

Listeners use `@ApplicationModuleListener`: async, in their own transaction, after the publisher commits, run on
a fixed 8-thread platform pool (`spring.task.execution`). Publications are persisted in `event_publication` and
republished on restart. Consequently listeners must tolerate **duplicate and out-of-order** delivery:
- book events carry the book version as `revision`; recommendation only replaces an embedding with a newer
  revision and keeps tombstones for deleted books;
- the order process checks the order's current state and ignores events that don't fit; reservations and
  payments are keyed by order id so repeated requests are idempotent.

Audited events implement `shared.Auditable` (action name, subject id, description, `actor`); `audit.AuditListener`
has one listener for that interface. The action must be upper snake case (≤ 50 characters) and the subject is
stored as `LongId.typeName()` + value, so audit depends only on `shared` and needs no change for new actions or
subjects. The `actor` (an `AccountId`, never a username) is captured at publish time via `shared.CurrentUser.id()`, because
listeners run with no signed-in user.

### Cross-cutting conventions

- **Typed ids**: ids are records (`BookId`, `AuthorId`, `OrderId`, `AccountId` (in `shared`), ...) wrapping `long` and implementing `shared.LongId`,
  persisted with Hibernate `UserType`s extending `shared.LongIdType` (not `AttributeConverter`, which isn't allowed on `@Id`). They
  serialize as plain numbers (`@JsonValue`) and bind from path/query params via a static `of(String)`. A new id
  type needs the record + its `*IdType`.
- **Change logging**: every data change is logged at INFO via `shared.ChangeLog`, which defers the line until
  commit. Use it for new mutations rather than logging directly. Logging is Log4j 2 (Logback is excluded).
- **Caching**: Caffeine via `@Cacheable`, wrapped in a transaction-aware cache manager (evictions apply on
  commit). Book/author responses embed related author/genre names, so renames evict broadly; see the eviction
  table in README.
- **Security**: all rules live in `security.SecurityConfiguration`; any unlisted path is **denied**, so new
  endpoints must be added there. HTTP Basic, stateless, CSRF off. Roles: `CUSTOMER`, `STAFF`, `ADMIN`.
  Data is owned by account id, never username: use `CurrentUser.requireId()` (`username()` is for display and
  logs); the sign-in principal is a `shared.AccountPrincipal`.
- **Errors**: every error is an RFC 9457 `ProblemDetail` (`application/problem+json`). A new exception gets
  `@ResponseStatus` and a message written for API clients, which becomes `detail` (`shared.ProblemDetailsAdvice`).
  Controller-local `@ExceptionHandler`s return `ProblemDetail`. Anything unhandled, plus 401/403 from the security
  filters, goes through `shared.ProblemDetailErrorController`, which shows only the status and path.
- **Actuator**: only `health`, `info`, `metrics` and `prometheus` are exposed (`management.endpoints.web.exposure`).
  Health and its probes are public, the rest is ADMIN-only (`SecurityConfiguration`).
- **OpenAPI**: springdoc generates `/v3/api-docs` (Swagger UI at `/swagger-ui.html`, both public) from the
  controllers under `/api/**`, with descriptions from **Javadoc** (therapi): on controller methods (first sentence =
  summary), `@param`s and request/response records, so write those for API clients. A new endpoint also needs
  `@ApiResponse` for each specific status, including its success one (springdoc drops the success response
  otherwise), `@SecurityRequirements` if it is public, and `@ResponseStatus` for a non-200 success. Its controller
  needs `@Tag`. Typed ids, pages and 400/401/403/problem responses are generic (`shared.OpenApiConfiguration`).
- **Pagination**: list endpoints return Spring Data pages with a fixed order per endpoint; `sort` is ignored;
  size default 20, max 100.
- **Virtual threads** serve HTTP requests (`shared.ThreadingConfiguration`).
- **MCP server**: modules expose read-only tools with `@McpTool` (`*McpTools` classes) at `/mcp`; tools return
  the same JSON as REST.
- **Null-safety**: every package is `@NullMarked` (JSpecify, in its `package-info.java`), so code is non-null unless
  marked `@Nullable`; a new package needs a `package-info.java` with `@NullMarked`. The build does not check them,
  but Spring reads them at runtime: `@Nullable` makes an MVC or MCP tool parameter optional, and Spring Data
  repositories reject `null` arguments and `null` single-entity results unless marked `@Nullable`.

### Database

Schema is owned by Flyway (`src/main/resources/db/migration`, `V<n>__*.sql`); Hibernate runs with
`ddl-auto: validate`, so every entity change needs a new migration with the next version number. Never edit an
existing migration. Migrations that transform data have dedicated tests (`AuthorMigrationTest`,
`PriceHistoryMigrationTest`).

### Demo data

The `demo` profile (enabled by `bootTestRun`) loads `src/main/resources/demo-data/*.json`. Catalog data is loaded
through the regular services; pricing and inventory each listen for new books and apply their own file by ISBN,
so modules stay independent. Security creates two demo staff accounts (`accounts.json`) after the initial admin,
which is only created while no account exists.

## Tests

- `TestcontainersConfiguration` (import it in every Spring test) provides the pgvector Postgres via
  `@ServiceConnection` and imports `TestAccounts`, which makes the auto-configured `RestTestClient` sign in as the
  test admin (`test-admin` / `test-admin-password`, set in `src/test/resources/application.properties`).
  Security tests build their own clients without these credentials.
- HTTP tests: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureRestTestClient`.
- Module tests (`*ModuleTests`): `@ApplicationModuleTest` boots a single module; drive it with Modulith's
  `Scenario` (publish an event, `andWaitForStateChange`, `andVerify`) instead of starting other modules.
- Mockito's agent is attached at JVM start by `build.gradle.kts`; don't rely on dynamic agent loading.
- `ModularityTests` also writes C4/PlantUML diagrams to `build/spring-modulith-docs`.
