# Design: account ids and deleting accounts

Status: proposed (2026-10-18).

## Goal

Everything outside `security` identifies a user by username (carts, orders, ratings, saved addresses, the audit
`actor`, the `customer` and `actor` fields of events), and `AccountService.delete` only removes the `account` row.
A username that is registered again after a deletion therefore inherits the old user's data. **A new account with a
reused username must inherit nothing** (no cart, orders, ratings, addresses or audit history), for every role, and
deleting must not erase records the shop needs.

Agreed with the user:
- **Accounts are identified by `AccountId`, not by username**, everywhere in the application. The username is only
  the login name and a display name. Ids are never reused, so reuse of a username cannot inherit anything.
- An account that no module keeps records for (a customer with no orders and no ratings; a staff member or admin with
  no audit entries) is **deleted completely**: the account row, and a customer's cart and saved addresses, go.
- Any other account is **soft-deleted**: the row stays but is anonymized and can't sign in; a customer's cart and
  saved addresses go; orders, ratings and audit entries stay and keep pointing at the account. The username is free
  again.
- On a soft-deleted customer's orders the **invoice address is kept** (accounting) and the **shipping address is
  scrubbed**.
- The existing rules stay (not your own account; not the last admin). Soft-deleted accounts don't count as admins.
- The API keeps returning usernames in the `customer` and `actor` fields (resolved when read) and gains
  `customerId` and `actorId`, so existing clients keep working.

Not included: restoring a deleted account, a retention period for soft-deleted rows, locking writers against a
concurrent delete (see section 5), and the internal `event_publication` outbox (not exposed; see the deployment note
in section 2).

## 1. Identity

- `AccountId` and `AccountIdType` move from `security` to `shared` (events and `CurrentUser` live there and can't
  depend on `security`). It stays a record wrapping `long`, implementing `shared.LongId`.
- `shared.AccountPrincipal` is a one-method interface (`AccountId accountId()`). `security.AccountUser` extends
  Spring's `User` and implements it; `AccountUserDetailsService` returns it. Sign-in is by username as before.
- `shared.CurrentUser` gets `@Nullable AccountId id()` (the signed-in account, or `null` as `username()` is today)
  and `AccountId requireId()` for controllers behind rules that guarantee a user. `username()` stays for display and
  logging only. Controllers and services take the id from `CurrentUser.requireId()` instead of `Principal.getName()`.
- Events carry the id instead of the username: `shared.Auditable.actor()` becomes `@Nullable AccountId`, and so do
  the `customer` and `actor` components of the events that have them (`StockReservationRequested`,
  `OrderProgressedEvent`, the book, author, price and stock events, `PaymentRequested`, `RefundRequested`, ...).

## 2. Data and migration

Owner columns change from the username (`customer VARCHAR(100)`, `actor VARCHAR(100)`) to the account id:

| Table | Column |
|---|---|
| `cart`, `cart_line` | `customer` becomes `customer_id BIGINT` (primary key and foreign key follow) |
| `customer_order` | `customer_id BIGINT NOT NULL`, index `(customer_id, created_at DESC)` |
| `book_rating` | `customer_id BIGINT NOT NULL`, primary key `(book_id, customer_id)` |
| `customer_address` | `customer_id BIGINT PRIMARY KEY` |
| `audit_entry` | `actor` becomes `actor_id BIGINT` (nullable, as `actor` is) |

Migration `V29__add_account_deleted_at.sql`: `ALTER TABLE account ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE`.

Migration `V30__identify_accounts_by_id.sql` converts existing rows, matching usernames to accounts
case-insensitively (usernames are unique that way):
- carts, cart lines and saved addresses whose username has no account are deleted (personal data of users who are
  gone);
- for every username in `customer_order`, `book_rating` or `audit_entry` that has no account, a placeholder account
  is inserted (role `CUSTOMER`, an unusable password hash, `deleted_at` set, username `~deleted-<its id>`), so no
  order, rating or audit entry is lost;
- the new `*_id` columns are filled from `account.id`, the old columns are dropped, and keys and indexes are
  recreated.

`AccountIdMigrationTest` (like `AuthorMigrationTest`) checks each of these on real rows, including a differently
cased username and an audit entry without an actor.

Deployment note: events in the `event_publication` outbox that were stored with usernames can't be read after the
upgrade, so the application must be stopped gracefully with no incomplete publications before upgrading. The README
says so.

## 3. `security`

Soft-deleted accounts count as nonexistent for sign-in, for username lookups and the duplicate-username check, and
are left out of `GET /api/accounts`.

Public API (module root package):

```java
/** Lets modules take part in deleting an account; consulted inside the deleting transaction. */
public interface AccountDeletionPolicy {

    /** Whether this module keeps records of the account that must survive the deletion (orders, ratings, audit). */
    boolean holdsRecords(AccountId account);

    /** Removes what must not outlive the account (cart, saved addresses). Called for every deletion. */
    void removePersonalData(AccountId account);

    /** Removes personal data from the records this module keeps; called only for a soft delete. */
    void anonymize(AccountId account);
}

public interface Accounts {

    /** Usernames to show for the given accounts; soft-deleted and unknown accounts map to "(deleted user)". */
    Map<AccountId, String> usernamesOf(Collection<AccountId> accounts);
}
```

`AccountService.delete(id, currentUser)` for any role, after the own-account and last-admin rules (the admin count
includes live accounts only): lock the account row `FOR UPDATE`; ask every `AccountDeletionPolicy` `holdsRecords`;
call `removePersonalData` on all of them; then
- nobody holds records: delete the row;
- somebody does: call `anonymize` on all of them, clear first and last name, replace the password hash with a hash
  of random bytes nobody knows, set `deleted_at`, and rename the account to the tombstone `~deleted-<accountId>`. `~`
  is not allowed in usernames (`[A-Za-z0-9._-]`), so nobody can register it; the original username is free.

Responses are unchanged for the caller (`204`). The change log says whether the account was deleted or anonymized and
logs no more personal data than it does today.

## 4. The modules (each implements `AccountDeletionPolicy`, internally)

| Module | `holdsRecords` | `removePersonalData` | `anonymize` |
|---|---|---|---|
| `ordering` | the account has an order | deletes the cart | sets the shipping address group of its orders to NULL (invoice kept) |
| `rating` | the account has a rating | nothing | nothing (ratings keep counting) |
| `customer` | false | deletes the saved addresses | nothing |
| `audit` | an entry has this account as `actor_id` | nothing | nothing (entries keep their `actor_id`) |

The all-or-nothing check on `customer_order` (V28) already allows a whole group to be NULL, so no schema change is
needed there. `OrderView.shipping` is `null` for scrubbed orders, as for orders from before addresses.

`OrderView` gains `customerId`; its `customer` is resolved with `Accounts.usernamesOf` (one batched call per page, so
the staff list costs one extra query). The audit entry response does the same for `actor` and gains `actorId`.
Soft-deleted or missing accounts show `(deleted user)`. Ratings expose no user.

## 5. What is not locked

Writers (cart, checkout, rating, saving an address) don't lock the account. A request that was already in flight
when its account was deleted can still leave a row under that account's id, e.g. an order, a rating or an audit
entry. Nobody can inherit it, because the id is never reused, and it shows as `(deleted user)`. The one visible
effect is that an order from such a request may keep a shipping address that deletion would have scrubbed. Requests
made after the delete fail at sign-in (`401`). This is accepted for a one-request window.

## 6. Module boundaries and docs

`ordering`, `rating`, `customer` and `audit` depend on `security` for `AccountDeletionPolicy`, and `ordering` and
`audit` for `Accounts` too; `security` still depends on `shared` only. `ModularityTests` must stay green. The README
(accounts section: ids, hard vs soft delete, the tombstone, sign-in refused, the `customerId`/`actorId` fields, the
deployment note, module dependency diagram), `CLAUDE.md` (typed ids, `CurrentUser`, audited events carry an
`AccountId`, `AccountDeletionPolicy` next to `BookDeletionPolicy`) and `docs/design/orders.md` and
`docs/design/customer-addresses.md` (owner is the account id, scrubbed shipping address) are updated.

## 7. Testing

- **Migration:** `AccountIdMigrationTest` as in section 2.
- **Per module** (`@ApplicationModuleTest`): `holdsRecords`, `removePersonalData` and `anonymize` of each policy
  against real tables (orders keep invoice and lose shipping; ratings keep their stars; audit entries keep their
  actor). The existing module and unit tests that pass username strings as the customer (carts, orders, ratings,
  addresses, audit) switch to account ids.
- **Integration, over HTTP, all modules:**
  - hard delete: a customer with a cart and a saved address and nothing else is deleted; the same username
    registers again and sees an empty cart, no addresses and no rating;
  - soft delete: a customer with an order and a rating is deleted; sign-in fails; the old username registers again
    as a clean account that sees none of the old orders, ratings or addresses; the old orders show
    `(deleted user)` and their `customerId`, `shipping` null, `invoice` still present; the rating still counts in
    the book's average; the account is absent from `GET /api/accounts`;
  - staff with no audit entries is hard-deleted; staff with audit entries is soft-deleted, and the same username
    registered again has an empty audit trail attributed to it while old entries show `(deleted user)`;
  - the last-admin and own-account refusals still work, and a soft-deleted admin doesn't count as an admin;
  - orders and the audit log still show usernames in `customer` and `actor` for live accounts, plus the new ids;
  - a deleted user's old credentials give `401`.
