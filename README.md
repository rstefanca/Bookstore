# Bookstore

Spring Boot 4 REST API backed by PostgreSQL, structured with Spring Modulith and tested with Testcontainers.

Requires Java 25 (LTS). If no Java 25 is installed, Gradle downloads Eclipse Temurin 25 for the build
(toolchain auto-provisioning via the foojay resolver plugin); running the built jar needs a Java 25 runtime.

## Modules

| Module | Package | Public API | Responsibility |
|--------|---------|------------|----------------|
| Security | `cz.codingmonkey.bookstore.security` | none | Staff accounts and the access rules for every endpoint |
| Shared | `cz.codingmonkey.bookstore.shared` | `LongId`, `LongIdType` | Technical building blocks (id persistence, caching setup), no domain logic |
| Book Catalog | `cz.codingmonkey.bookstore.bookcatalog` | `BookId`, `AuthorId`, `GenreId`, `BookDetails`, `BookSearch`; `BookCreatedEvent`, `BookUpdatedEvent`, `BookDeletedEvent`, `AuthorCreatedEvent` events; `BookCatalog`; `BookDeletionPolicy` SPI | Books, authors and genres (implementation in `bookcatalog.internal`) |
| Inventory | `cz.codingmonkey.bookstore.inventory` | `StockAdjustedEvent`; stock reservation request/result events | Stock level per book; blocks deleting books in stock (implementation in `inventory.internal`) |
| Pricing | `cz.codingmonkey.bookstore.pricing` | `Money`, `Prices`; `PriceChangedEvent` | List price per book in the store currency (implementation in `pricing.internal`) |
| Storefront | `cz.codingmonkey.bookstore.storefront` | none | Customer-facing book reads: catalog details plus price |
| Ordering | `cz.codingmonkey.bookstore.ordering` | `OrderId` | Carts, orders and the order process (implementation in `ordering.internal`) |
| Payment | `cz.codingmonkey.bookstore.payment` | `PaymentReference`; request/result events | Fake payment provider: charges, declines, refunds |
| Audit | `cz.codingmonkey.bookstore.audit` | none | Records an audit trail of every event that implements `shared.Auditable` (book, author, stock, price and order events) |
| Recommendations | `cz.codingmonkey.bookstore.recommendation` | none | Similar-book suggestions from text embeddings stored with pgvector |
| Ratings | `cz.codingmonkey.bookstore.rating` | `Ratings`, `RatingSummary` | Customers' 1-5 star ratings of books and per-book summaries (implementation in `rating.internal`) |

### Security

Staff sign in with HTTP Basic on every request (no sessions or cookies, so CSRF protection is off). Accounts live
in the `account` table with BCrypt password hashes. Customers are accounts too: anyone can sign up with
`POST /api/accounts/register` (always role `CUSTOMER`) and then sign in the same way.

| Who | May |
|-----|-----|
| Anyone | Browse books, authors, genres, prices and recommendations; use the read-only MCP tools; check health; read the OpenAPI spec |
| `STAFF` | Also create, change and delete books, authors and genres; manage prices and inventory; read price history |
| `ADMIN` | Everything staff may, plus read the audit log, manage accounts and use the other actuator endpoints |
| `CUSTOMER` | Browse like anyone, see and change their own account, use a cart and place orders, rate books; signs up with `POST /api/accounts/register` |

All rules are in `security.SecurityConfiguration`, and any path without a rule is denied, so new endpoints are
closed until they're added there. Wrong credentials get 401 even on public endpoints; a signed-in account without
the role gets 403.

The first admin is created at startup when no account exists: `bookstore.security.initial-admin.username`
(default `admin`) with the password from `bookstore.security.initial-admin.password` and, optionally, a name
(`first-name`, `last-name`). Without a configured
password, a random one is generated and logged once (WARN). Admins manage accounts through `/api/accounts`;
every account has a first and last name (accounts created before names existed have
none until set), and passwords need 12-72 bytes (BCrypt ignores anything past 72). You can't delete your own account, and the last admin
can't be deleted, even by two admins deleting each other at the same moment (admin rows are locked while checking).

Audited events implement `shared.Auditable` and describe themselves: the audit action name, the subject (any
`shared.LongId`) and the description text. The audit module has a single listener for `Auditable` and depends only on
`shared`. The action is stored as given (upper snake case, at most 50 characters), and the subject as
`LongId.typeName()` plus the id value (`subject_type`, `subject_id`), so a new audited event or subject type needs no
change in the audit module. An event that sets its own `occurredAt` and is delivered again is skipped when an entry
with the same subject, action and `occurredAt` exists.

Audit entries record who made each change (`actor`: the username). The audited events carry the actor, taken
from the signed-in user when the change is made (`shared.CurrentUser`), because the audit listeners run in the
background where nobody is signed in. Follow-up changes inherit it: the price removed after a book was deleted is
attributed to whoever deleted the book. Changes without a signed-in user (startup, background work) and entries
recorded before actors existed have `actor: null`.

Purchases are audited step by step. The ordering module reports every step it sees (`OrderProgressedEvent`):
`ORDER_PLACED`, `ORDER_STOCK_RESERVED` or `ORDER_STOCK_MISSING`, `ORDER_PAYMENT_CHARGED` or `ORDER_PAYMENT_DECLINED`,
`ORDER_CONFIRMED`, `ORDER_REJECTED`, `ORDER_CANCELLED`, and the undo steps `ORDER_STOCK_RELEASED` and
`ORDER_PAYMENT_REFUNDED`. Each entry has the order as subject and the customer as actor, also for steps done in the
background. Each step is recorded once per order, even when its event is delivered again (see above). Every audit log
line names only the action and the subject. `GET /api/audit?subjectType=ORDER&subjectId=N` shows one purchase. Full
design: `docs/design/purchase-audit.md`.

Checking a BCrypt password takes tens of milliseconds, on every request with HTTP Basic. That's fine for staff
tools; for high traffic, exchanging credentials for a token or session once would avoid the repeated cost.

### Books and authors

`Author` is a separate aggregate within the catalog module. A book references one or more authors by `AuthorId`
in credited order (`book_author.position`) instead of holding author objects. Books are created and updated with
`authorIds` of existing authors. Unknown or duplicate ids return 400. An author who still has books can't be
deleted (409); the `book_author` foreign key also covers concurrent requests. Responses include each author's id and
name, and `BookCreatedEvent` carries the author names, so other modules never need to know about authors.

Migration `V6` turns the former free-text `book.author` column into one author per distinct trimmed name and links
each book to it. `AuthorMigrationTest` covers this.

Genres are a separate aggregate in the catalog too. A book has any number of genres, unordered (`book_genre`),
given as `genreIds` (optional). Genre names are unique regardless of case, enforced by the service and by a
unique index on `lower(name)`. A genre in use can't be deleted (409). Migration `V7` seeds a starting list of
common genres; from then on they're managed through `/api/genres`. Authors can have favorite genres
(`favoriteGenreIds`, optional, unordered). Deleting a genre removes it from authors' favorites instead of being
blocked. Book responses list authors as `{id, name}` only; favorites are returned by `/api/authors`.

Book list filters (`author`, `authorId`, `genreId`) combine with AND.

### Recommendations

`GET /api/books/{id}/similar` suggests books similar to a given one. The recommendation module embeds each book's
title, authors, genres and description with all-MiniLM-L6-v2 (384 dimensions), run in-process through Spring AI's ONNX
`TransformersEmbeddingModel`, so there's no embedding service to operate. Vectors live in the `book_embedding`
table (pgvector, HNSW index, cosine distance) next to the rest of the data.

The module keeps its vectors in sync by listening to `BookCreatedEvent`, `BookUpdatedEvent` and `BookDeletedEvent`,
like audit does. Listeners run asynchronously and may see events out of order or more than once (on retries). Each
event therefore carries the book's version as `revision`, and an embedding is only replaced by a newer revision. A
deleted book leaves a tombstone row so a late event can't bring it back. Embeddings appear shortly after a book is
created; until then `/similar` returns 404 for it.

The model is downloaded on first start, pinned to the Spring AI 2.0.1 release, and cached in
`bookstore.recommendation.cache-dir`. Point `bookstore.recommendation.model-uri` and `tokenizer-uri` at a local copy
to start without internet access.

The embedded text is plain sentences with the genres at both ends, e.g. "Science Fiction book. Dune by Frank
Herbert. On the desert planet Arrakis... Genre: Science Fiction." On a 9-book, 3-genre evaluation this format put
a same-genre book first for 8 of 9 books (7 of 9 without genres). Books sharing a topic score highest (a sequel
~0.8); a shared genre helps, but less than a shared topic.

Renaming an author or genre doesn't update existing embeddings; they're refreshed when the book is next edited.

### Prices

The pricing module holds one list price per book. A book without a price is in the catalog but not for sale.
Prices are `Money` values (a `BigDecimal` amount with the currency's decimal places, plus an ISO 4217 currency) in
the store currency, `bookstore.pricing.currency` (EUR). Each price stores its currency, so adding a currency later
needs no data migration. Setting a price locks the book against deletion like restocking does; deleting a book
removes its price. Every change publishes `PriceChangedEvent` (old and new price, `null` meaning not for sale),
which the audit module records as `PRICE_CHANGED`, e.g. "Price changed from 10.00 EUR to 8.50 EUR".

The catalog doesn't know about prices; the storefront module combines the two (see below).

`GET /api/books/{id}/price-history` lists the periods during which a book had each price, newest first:

    {"content": [
       {"price": {"amount": 7.99, "currency": "EUR"}, "from": "2026-10-20T09:00:00Z", "until": null},
       {"price": {"amount": 10.00, "currency": "EUR"}, "from": "2026-10-01T08:30:00Z", "until": "2026-10-20T09:00:00Z"}
     ], "page": {...}}

`until` is `null` for the current price. A gap between periods means the book wasn't for sale. History is written
in the same transaction as the price change (`book_price_period`, at most one open period per book), and is kept
when a book is deleted. Prices that existed before the history was introduced (migration `V13`) start at the
migration time.

### Storefront

`GET /api/books` and `GET /api/books/{id}` belong to the storefront module, which combines the catalog's book
details with the current price (`"price": null` means not for sale) and the book's rating summary. It uses only
the public APIs `BookCatalog`, `Prices` and `Ratings`, so the catalog, pricing and rating modules still don't depend
on each other. Creating, updating and deleting books
stays with the catalog (`POST`/`PUT`/`DELETE /api/books`).

Every storefront book carries a `rating` summary, never null:

```json
"rating": {"average": 4.3, "count": 12, "stars": {"1": 0, "2": 1, "3": 0, "4": 3, "5": 8}}
```

An unrated book has `"average": null`, `"count": 0` and all star counts 0. There is no sorting or filtering by
rating.

For a single book, the catalog, price and rating queries run in parallel on virtual threads; none needs another's
result. This uses `CompletableFuture`; structured concurrency (`StructuredTaskScope`) would fit better but is still
a preview API in Java 25. A list runs one query for the page of books and then, in parallel, one batch query for
their prices and one for their rating summaries, since both need the page's book ids. A price filter (`minPrice`,
`maxPrice`) first asks pricing which books are in range (`Prices.booksPricedBetween`) and limits the catalog search
to them (`BookSearch.onlyBooks`), so paging and totals stay right without either module reading the other's tables.
For a very large catalog, a broad range would mean a long id list; a search read model would then be the better fit.
Composed responses aren't cached as a whole; each part is cached by the module that owns it (see Caching), and
rating summaries aren't cached.

### Threads

HTTP requests run on virtual threads (`shared.ThreadingConfiguration`), since they mostly wait for the database.
Since Java 24, virtual threads no longer pin their carrier thread inside `synchronized` blocks, so blocking JDBC
calls in libraries that still use `synchronized` don't reduce request throughput.
Background work, the async event listeners, runs on a fixed pool of 8 platform threads (`spring.task.execution`):
computing embeddings is CPU-bound native code that gains nothing from virtual threads, and the pool bounds and
queues that work by itself. On shutdown the app waits up to 30 seconds for running listener work.

### Orders

Customers fill a cart (`/api/cart`) and check out. Checkout fixes titles and prices, creates the order
(`PENDING`) and answers `202 Accepted` right away; the rest happens in the background:

    PENDING --stock reserved--> AWAITING_PAYMENT --paid--> CONFIRMED --customer cancels--> CANCELLED
       |                              |
       +--no stock--> REJECTED        +--payment failed--> REJECTED (stock put back)

The ordering module's `OrderProcess` drives it with events. Inventory reserves all lines or none
(`StockReservationRequested` -> `StockReserved` / `StockReservationFailed`); the fake payment module charges
(`PaymentRequested` -> `PaymentSucceeded` / `PaymentFailed`, declining amounts above
`bookstore.payment.decline-above`, default 100 EUR, to make failures easy to try). Undo steps are
`StockReleaseRequested` and `RefundRequested`. Inventory and payment define these events and don't know about
orders, so dependencies only go from ordering to them (no cycle).

Events may arrive twice or late (retries): orders only move on from the state a step expects; reservations and
payments are stored by the order id, so repeated requests get the earlier answer; releases and refunds lock their
row, so they happen once; the order notes each undo step it has seen (stock released, payment refunded), so a
repeated result doesn't report it again. Full design: `docs/design/orders.md`.

### Ratings

Customers (`CUSTOMER`) rate a book with 1 to 5 stars (`PUT /api/books/{id}/rating`, body `{"stars": 4}`). Each
customer has one rating per book (usernames compare case-insensitively); rating again replaces it, and ratings can't
be removed. Staff and admins can't rate. `stars` must be a whole number from 1 to 5, else 400. `GET` on the same path
returns your own rating, or 404 if you haven't rated the book. The rating module checks that the book exists and
locks it against deletion while saving, like restock and set-price; deleting a book removes its ratings
(`BookDeletedEvent`). Design: `docs/design/book-ratings.md`.

### Pagination

List endpoints (`/api/books`, `/api/authors`, `/api/genres`, `/api/inventory`, `/api/prices`,
`/api/books/{id}/price-history`, `/api/audit`) return pages:

    GET /api/books?author=herbert&page=0&size=20
    {"content": [ ...books... ], "page": {"size": 20, "number": 0, "totalElements": 57, "totalPages": 3}}

`page` is 0-based; `size` defaults to 20 and is capped at 100. Filters apply before paging. Each list has a fixed
order (books by title, authors and genres by name, inventory by book id, audit newest first); a `sort` parameter
is ignored. `/api/books/{id}/similar` is a top-N list with `limit`, not paged, and the MCP tools keep their own
limits.

### Errors

Every error response is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem detail with content type
`application/problem+json`:

    GET /api/books/999999
    404 {"title": "Not Found", "status": 404, "detail": "Book 999999 not found", "instance": "/api/books/999999"}

`type` is left out (it is `about:blank`, so `title` is just the HTTP reason phrase). `detail` explains what went
wrong in words meant for the caller. Invalid request bodies and parameters also list each problem under `errors`:

    POST /api/books {"title": " ", "authorIds": [], ...}
    400 {"title": "Bad Request", "status": 400, "detail": "Invalid request content.", "instance": "/api/books",
         "errors": [{"field": "title", "message": "must not be blank"}, {"field": "authorIds", "message": "must not be empty"}]}

Where they come from (all in `shared`, except the per-controller handlers):

- Module exceptions declare their status with `@ResponseStatus`, and their message becomes `detail`
  (`shared.ProblemDetailsAdvice`), so those messages are written for API clients.
- Spring MVC's own errors (malformed JSON, a wrong parameter type, an unsupported method, `ResponseStatusException`)
  go through Spring's `ResponseEntityExceptionHandler`, which the same advice extends.
- Conflicts that only make sense in one controller, such as a concurrent change, are handled there and return a
  `ProblemDetail` too.
- Anything that reaches the servlet error page, meaning 401/403 from the security filters and unexpected exceptions
  (500), is rendered by `shared.ProblemDetailErrorController` with only the status and path. It never includes the
  exception's message, which could expose internals.

### Logging

Logging uses Log4j 2 (2.26.1, the latest stable release; Log4j 3 is still in beta) through
`spring-boot-starter-log4j2` instead of Spring Boot's default Logback. Application code logs through the Log4j API;
libraries that use SLF4J or Java Util Logging are routed into Log4j 2 as well.

Every data change is logged at INFO, for example:

    INFO ... c.c.b.b.i.BookService    : Created book 1 "Dune" (ISBN 9780441013593)
    INFO ... c.c.b.p.i.PricingService : Changed price of book 1 from 10.00 EUR to 8.50 EUR
    INFO ... c.c.b.a.AuditListener    : Recorded audit entry PRICE_CHANGED for book 1

This covers creating, updating and deleting books, authors and genres; stock and price changes; and the changes
event listeners make (audit entries, embeddings, stock and price cleanup after a book is deleted). Changes are logged
through `shared.ChangeLog`, which defers the line until the transaction commits, so a rolled-back change is never
logged. Levels can be set per package as usual, e.g. `logging.level.cz.codingmonkey.bookstore.recommendation=DEBUG`
also shows events the recommendation module ignored as outdated.

### Operations (actuator)

Spring Boot Actuator serves operational endpoints under `/actuator`. Only these are exposed:

| Endpoint | Who | What |
|----------|-----|------|
| `/actuator/health` | anyone | `{"status": "UP"}`; admins also see each component (`db`, `diskSpace`, ...) |
| `/actuator/health/liveness`, `/actuator/health/readiness` | anyone | Probes for orchestrators and load balancers; readiness also needs the database |
| `/actuator/info` | admin | Build (name, version from `build-info.properties`) and Java runtime |
| `/actuator/metrics` | admin | Micrometer metrics, e.g. `/actuator/metrics/http.server.requests` |
| `/actuator/prometheus` | admin | The same metrics in Prometheus format, for scraping with an admin's HTTP Basic credentials |

Other endpoints (`env`, `beans`, `heapdump`, ...) aren't exposed, and the rules are in `security.SecurityConfiguration`
like every other path. The build info leaves out the build time, so Gradle can skip an unchanged build.

### OpenAPI spec

springdoc generates the spec at runtime from the controllers, request records and validation annotations. It covers
only `/api/**`: `/mcp` and `/actuator` are documented above.

The texts come from Javadoc, kept at runtime by therapi-runtime-javadoc: a controller method's first sentence is the
operation's summary and the rest its description, `@param` describes a request parameter, and a record's Javadoc
and `@param` tags describe its schema and fields. So that Javadoc is written for API clients. Annotations add what
Javadoc can't say: `@Tag` (with the group's description) on each controller, `@ApiResponse` for each status an
operation can answer, `@SecurityRequirements` (empty) on public operations and `@Schema(example = ...)` on request
fields. The overview at the top of Swagger UI is `src/main/resources/openapi/overview.md`.

Generic parts, so the spec matches the JSON (`shared.OpenApiConfiguration`):

- Typed ids (any `shared.LongId`) are integers, also as path and query parameters, and `Money.currency` is its
  ISO code.
- Lists show `page` and `size` (with the default and the cap of 100); `sort` isn't offered, since it's ignored.
- Every 4xx/5xx response is `application/problem+json` with the `Problem` schema (see Errors); operations with a
  body also list 400, and those that need a signed-in account 401 and 403. A `default` problem response is
  declared once on `shared.ProblemDetailsAdvice`.
- Success statuses come from `@ResponseStatus` (201 for creates, 202 for checkout, 204 for deletes), also where the
  method returns a `ResponseEntity` to set `Location`. A method with `@ApiResponse`s for its errors must declare its
  success response too (without content; springdoc fills in the body schema), or springdoc leaves it out.
- The security module declares HTTP Basic.

### Caching

Books and authors by id (`GET /api/books/{id}`, `GET /api/authors/{id}` and the `get_book`/`get_author` MCP tools)
are cached in memory with Caffeine (Spring's `@Cacheable`). Responses include names of related authors and genres,
so evictions follow those relations:

| Change | Evicts |
|--------|--------|
| Book updated or deleted | that book |
| Author updated (name, favorites) | that author, all books |
| Author deleted | that author (authors with books can't be deleted) |
| Genre renamed | all books and authors |
| Genre deleted | all authors (it disappears from favorites; genres used by books can't be deleted) |

The pricing module caches `Prices.priceOf` (the current price by book id, including "not for sale"), which the
storefront uses for single books. Setting, changing or removing a price, and deleting the book, evict that book's
entry. The batch lookup for lists isn't cached.

Evictions take effect only when the transaction commits (`TransactionAwareCacheManagerProxy`). Entries expire
after 10 minutes at the latest (`bookstore.cache.caffeine-spec`), which also bounds staleness from changes made
outside the services, such as directly in the database. The caches are per instance: running several instances
would need a shared cache (e.g. Redis) or cross-instance invalidation.

### MCP server (for AI clients)

The app is also a [Model Context Protocol](https://modelcontextprotocol.io) server, so AI assistants can browse the
catalog. It speaks Streamable HTTP at `http://localhost:8080/mcp`, through Spring AI's MCP server starter. Each
module contributes its own tools with `@McpTool`, and all of them are read-only (marked `readOnlyHint`):

| Tool | Module | What it does |
|------|--------|--------------|
| `search_books` | storefront | Books with prices and ratings; filter by title, author name, `authorId`, `genreId`, `minPrice`/`maxPrice` (AND, price bounds inclusive); sorted by title; `limit` 1-50, default 20 |
| `get_book` | storefront | One book by id, with its price and rating |
| `list_authors` | catalog | Authors sorted by name, optional name filter, `limit` 1-50 |
| `get_author` | catalog | One author by id, with favorite genres |
| `list_genres` | catalog | All genres, optional name filter |
| `find_similar_books` | recommendation | Similar books for a book id, `limit` 1-20, default 5 |
| `get_book_prices` | pricing | Prices for up to 50 book ids; `price` is null for books not for sale |

Tools take plain numeric ids and return the same JSON as the REST API. Unknown ids and invalid limits come back as
tool errors with a readable message.

Connect Claude Code with `claude mcp add --transport http bookstore http://localhost:8080/mcp`, or explore the
tools with the MCP Inspector (`npx @modelcontextprotocol/inspector`, transport "Streamable HTTP"). There's no
authentication, so anyone who can reach `/mcp` can read the catalog, the same as with the REST API.

### Typed ids

Ids are records (`BookId`, `AuthorId`, `GenreId`, `OrderId`, `AuditEntryId`, ...) wrapping a `long`, not bare `Long`s.
All of them implement `shared.LongId` (`long value()`). They are stored as `BIGINT` through Hibernate `UserType`s
built on `shared.LongIdType`, which only needs the id class and its constructor. A JPA `AttributeConverter` is not
allowed on `@Id` attributes, which is why a `UserType` is used. In JSON they are plain numbers (`@JsonValue`).

The public ids that come from outside (`BookId`, `AuthorId`, `GenreId`, `OrderId`, `AccountId`, `ReservationId`,
`PaymentReference`) reject zero and negative values in their constructor via `LongId.requirePositive`. The ones
bound from requests do so through their static `of(String)` factory, so malformed or non-positive ids in path
variables and query parameters return 400. `AuditEntryId` and `PricePeriodId` are only created from
database-generated keys and don't check.

Dependencies only go one way: inventory depends on book. Audit receives every event that implements
`shared.Auditable` and depends only on `shared`, so it doesn't depend on the modules that publish them.

    inventory ──BookCatalog.existsAndLockAgainstDeletion──▶ book   (restock checks the book exists)
    inventory ──implements BookDeletionPolicy─────────────▶ book   (vetoes deleting books in stock)
    inventory ◀──BookDeletedEvent──── book                              (removes the empty stock record)
    recommendation ◀──BookCreatedEvent/BookUpdatedEvent/BookDeletedEvent── book
    pricing   ──BookCatalog.existsAndLockAgainstDeletion──▶ book   (setting a price checks the book exists)
    pricing   ◀──BookDeletedEvent── book                             (removes the price)
    audit     ◀──Auditable events── book, inventory, pricing, ordering
    storefront ──BookCatalog.findBook/findBooks──▶ book
    storefront ──Prices.priceOf/pricesOf────────▶ pricing
    storefront ──Ratings.summaryOf/summariesOf──▶ rating

A new book has no stock record. One is created on its first restock, so until then `/api/inventory/{bookId}`
returns 404. Deleting a book whose stock is above 0 returns 409.

The book module consults `BookDeletionPolicy` implementations instead of calling inventory. That keeps book
unaware of inventory and avoids a cyclic module dependency. To stop a restock and a delete from running at the
same time, restock takes a `FOR SHARE` lock on the book row and delete takes `FOR UPDATE` before checking the
policies (see `RestockDeleteRaceTest`).

Event listeners use `@ApplicationModuleListener`, so they run asynchronously in their own transaction after the
publisher commits.
Publications are stored in the `event_publication` table (JDBC event publication registry), so incomplete
events are republished on restart.

`ModularityTests` checks module boundaries (for example, `audit` must not use `bookcatalog.internal`) and writes
C4/PlantUML diagrams to `build/spring-modulith-docs`. `BookModuleTests` and `AuditModuleTests` use
`@ApplicationModuleTest` to start one module at a time and Modulith's `Scenario` API to test the event flow.

## Run tests (needs Docker)

    ./gradlew test

## Run locally against a throwaway Postgres container

    ./gradlew bootTestRun

This starts with demo data: 18 authors and 26 books (classics, science fiction, fantasy, mysteries...) with
genres, co-authors and descriptions, loaded by the `demo` profile (`bookcatalog.internal.DemoCatalogData`, data in
`src/main/resources/demo-data/catalog.json`). It is loaded through the regular services, so recommendations and
audit entries are created as for any other change, and only into an empty catalog.

Prices and stock are demo data of the pricing and inventory modules (`demo-data/prices.json`,
`demo-data/stock.json`): with the `demo` profile, each module listens for new books and prices or stocks those whose
ISBN is in its file, so modules stay independent. 24 books get a price in EUR and 23 get stock; a few are left out
on purpose (*The Three-Body Problem* and *The Silmarillion* aren't for sale, *Cosmos* is out of stock). The demo ISBNs are
placeholders and don't identify real editions. Run any other setup with `--spring.profiles.active=demo` to get the
same data; without the profile nothing is loaded.

Or run `BookstoreApplication` against your own Postgres (see `application.yml`, default `localhost:5432/bookstore`,
user/pass `bookstore`). It needs the pgvector extension installed (e.g. the `pgvector/pgvector:pg17` image); the
`V11` migration enables it with `CREATE EXTENSION`, which needs sufficient database privileges.

## API

The API is described as OpenAPI 3.1 at `/v3/api-docs` (JSON; `/v3/api-docs.yaml` for YAML) and can be tried out in
Swagger UI at `/swagger-ui.html`. Both are open to anyone. Use the Authorize button to sign in with HTTP Basic.
Generated by [springdoc](https://springdoc.org) from the controllers (see "OpenAPI spec" below); the table here is a
summary.

| Method | Path                       | Description                 |
|--------|----------------------------|-----------------------------|
| GET    | /api/books?title=...&author=...&authorId=...&genreId=...&minPrice=...&maxPrice=... | List books with prices and ratings, sorted by title; optional filters combine: title (contains), author name (contains, any co-author), author id, genre id, price range (inclusive, EUR; leaves out books not for sale) |
| GET    | /api/books/{id}            | Get one book with its price and rating |
| POST   | /api/books                 | Create a book               |
| PUT    | /api/books/{id}            | Update a book               |
| DELETE | /api/books/{id}            | Delete a book; 409 if in stock |
| PUT    | /api/books/{id}/rating     | Rate a book, body `{"stars": 4}` (1-5); replaces your earlier rating; 400 for other values, 404 if the book doesn't exist (customer) |
| GET    | /api/books/{id}/rating     | Your rating of a book; 404 if you haven't rated it (customer) |
| GET    | /api/authors?name=...      | List authors sorted by name (optional name filter) |
| GET    | /api/authors/{id}          | Get one author              |
| POST   | /api/authors               | Create an author, body `{"name": "...", "favoriteGenreIds": [9]}` (favorites optional) |
| PUT    | /api/authors/{id}          | Replace an author's name and favorite genres (omitted favorites are cleared) |
| DELETE | /api/authors/{id}          | Delete an author; 409 if they have books |
| GET    | /api/genres?name=...       | List genres sorted by name (optional name filter) |
| GET    | /api/genres/{id}           | Get one genre               |
| POST   | /api/genres                | Create a genre, body `{"name": "..."}`; 409 if the name exists (any case) |
| PUT    | /api/genres/{id}           | Rename a genre; 409 if the name exists |
| DELETE | /api/genres/{id}           | Delete a genre; 409 if assigned to books; removed from authors' favorites |
| GET    | /api/inventory             | Stock levels of all books   |
| GET    | /api/inventory/{bookId}    | Stock level of one book     |
| POST   | /api/inventory/{bookId}/restock  | Add stock, body `{"quantity": 5}`; 404 if the book doesn't exist |
| POST   | /api/inventory/{bookId}/withdraw | Remove stock, body `{"quantity": 2}`; 409 if not enough in stock |
| GET    | /api/books/{id}/price      | A book's price; 404 if it has none (not for sale) |
| PUT    | /api/books/{id}/price      | Set or change the price, body `{"amount": 12.90}` (`currency` optional, must be EUR); 404 if the book doesn't exist |
| DELETE | /api/books/{id}/price      | Remove the price (take the book off sale) |
| GET    | /api/books/{id}/price-history | Price periods, newest first (paged); empty if never priced |
| GET    | /api/prices?bookId=...     | Prices by book id (paged), optionally only for the given books (repeat `bookId`) |
| GET    | /api/books/{id}/similar?limit=5 | Similar books, most similar first, with `similarity` (cosine, higher is closer); limit 1-20; 404 until the book's embedding exists |
| GET    | /api/accounts              | Accounts, sorted by username (admin) |
| POST   | /api/accounts              | Create an account, body `{"username": "...", "firstName": "...", "lastName": "...", "password": "...", "role": "STAFF"}` (admin) |
| PUT    | /api/accounts/{id}         | Change an account's name, body `{"firstName": "...", "lastName": "..."}` (admin) |
| PUT    | /api/accounts/{id}/password | Reset an account's password, body `{"password": "..."}` (admin) |
| DELETE | /api/accounts/{id}         | Delete an account; 409 for your own or the last admin (admin) |
| POST   | /api/accounts/register     | Sign up as a customer, body `{"username": "...", "firstName": "...", "lastName": "...", "password": "..."}` (anyone) |
| GET    | /api/accounts/me           | The signed-in account (any role) |
| PUT    | /api/accounts/me           | Change your own name, body `{"firstName": "...", "lastName": "..."}` |
| PUT    | /api/accounts/me/password  | Change your password, body `{"currentPassword": "...", "newPassword": "..."}` |
| GET    | /api/cart                  | Your cart with current prices and total (customer) |
| PUT    | /api/cart/items/{bookId}   | Set quantity, body `{"quantity": 2}` (0 removes; max 20); 409 if not for sale (customer) |
| DELETE | /api/cart/items/{bookId}   | Remove a book (customer) |
| DELETE | /api/cart                  | Empty the cart (customer) |
| POST   | /api/cart/checkout         | Place the order: 202 with the `PENDING` order and its `Location`; 409 for an empty cart or books no longer for sale (customer) |
| GET    | /api/orders                | Your orders, newest first (customer) |
| GET    | /api/orders/{id}           | One of your orders: status, lines, total, rejection reason; 404 for other customers' orders (customer) |
| POST   | /api/orders/{id}/cancel    | Cancel a confirmed order: stock back, payment refunded; 409 otherwise (customer) |
| GET    | /api/orders/all            | All customers' orders (staff) |
| GET    | /api/audit?subjectType=...&subjectId=... | Audit trail with who made each change (`actor`), newest first; optionally for one subject, e.g. `BOOK`, `AUTHOR` or `ORDER` with its id (both parameters or neither); each entry has `subject: {type, id}` (admin) |
| GET    | /actuator/health           | Health status, plus `/liveness` and `/readiness` probes (anyone; components for admins) |
| GET    | /actuator/info, /actuator/metrics, /actuator/prometheus | Build info, metrics and the Prometheus scrape (admin); see Operations |

    curl -u admin:<password> -X POST localhost:8080/api/authors -H 'Content-Type: application/json' \
      -d '{"name":"Frank Herbert","favoriteGenreIds":[21]}'
    # => {"id":1,"name":"Frank Herbert","favoriteGenres":[{"id":21,"name":"Science Fiction"}]}

    curl localhost:8080/api/genres?name=science
    # => [{"id":20,"name":"Science"},{"id":21,"name":"Science Fiction"}]

    curl -u admin:<password> -X POST localhost:8080/api/books -H 'Content-Type: application/json' \
      -d '{"title":"Dune","authorIds":[1],"genreIds":[21],"isbn":"9780441013593","publishedYear":1965,"description":"Paul Atreides and the desert planet Arrakis."}'
