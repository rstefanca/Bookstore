# Design: deleting accounts

Status: proposed (2026-10-18).

## Goal

Everything outside `security` identifies a user by username (carts, orders, ratings, saved addresses, the audit
`actor`), and `AccountService.delete` only removes the `account` row. A username that is registered again after a
deletion therefore inherits the old user's data. **A new account with a reused username must inherit nothing** (no
cart, orders, ratings, addresses or audit history), and deleting must not erase records the shop needs. This holds
for every role.

Agreed with the user:
- An account whose username no module keeps records for (a customer with no orders, no ratings; a staff member or
  admin with no audit entries) is **deleted completely**: the account row, a customer's cart and saved addresses
  go. The username is free again.
- Otherwise the account is **soft-deleted**: the row stays but is anonymized and can't sign in; a customer's cart and
  saved addresses go; orders, ratings and audit entries stay, detached from the username (book averages, the shop's
  order records and the audit trail don't change). The username is free again.
- On a soft-deleted customer's orders the **invoice address is kept** (accounting) and the **shipping address is
  scrubbed**.
- The existing rules stay (not your own account; not the last admin). Soft-deleted accounts don't count as admins.
- Identity does **not** change to account ids; the username stays the key.

Not included: switching to account ids, restoring a deleted account, a retention period for soft-deleted rows, the
internal `event_publication` outbox (it is not exposed and completed rows are not shown to anyone).

## 1. `security`

Migration `V29__add_account_deleted_at.sql`: `ALTER TABLE account ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE`
(nullable; set only on soft-deleted accounts).

Soft-deleted accounts count as nonexistent for sign-in (`AccountUserDetailsService`), for username lookups and the
duplicate-username check, and are left out of `GET /api/accounts`. Their row is renamed so the original username is
free (below), so a lookup by the old username never finds them.

Public API (module root package):

```java
/** Lets modules take part in deleting an account; consulted inside the deleting transaction. */
public interface AccountDeletionPolicy {

    /** Whether this module keeps records of the user that must survive the deletion (orders, ratings, audit). */
    boolean holdsRecords(String username);

    /** Removes what must not outlive the account (cart, saved addresses). Called for every deletion. */
    void removePersonalData(String username);

    /** Detaches the records this module keeps from the username; called only for a soft delete. */
    void anonymize(String username, String tombstone);
}

public interface Accounts {

    /** Whether a live account with this username exists; takes a FOR KEY SHARE lock on it until commit. */
    boolean existsAndLockAgainstDeletion(String username);
}
```

`AccountService.delete(id, currentUsername)` for **any role** (after the own-account and last-admin rules, where
the admin count only includes live accounts): lock the account row `FOR UPDATE`; ask every `AccountDeletionPolicy` `holdsRecords`; call `removePersonalData` on all of them; then
- nobody holds records: delete the row;
- somebody does: call `anonymize(username, tombstone)` on all of them, clear first and last name, replace the
  password hash with a hash of random bytes that nobody knows, set `deleted_at`, and rename the account to the
  tombstone `~deleted-<accountId>`. `~` is not allowed in usernames (`[A-Za-z0-9._-]`), so nobody can register it.

Responses are unchanged for the caller (`204`). The change log says whether the account was deleted or anonymized and
logs no more personal data than it does today.

## 2. The modules (each implements `AccountDeletionPolicy`, internally)

| Module | `holdsRecords` | `removePersonalData` | `anonymize(old, tombstone)` |
|---|---|---|---|
| `ordering` | the user has an order | deletes the cart | `customer_order.customer` becomes the tombstone; the shipping address group is set to NULL (invoice kept) |
| `rating` | the user has a rating | nothing | `book_rating.customer` becomes the tombstone |
| `customer` | false | deletes the saved addresses | nothing |
| `audit` | an entry has this username as its `actor` | nothing | `audit_entry.actor` becomes the tombstone; if entry descriptions embed the username as text, those are rewritten too |

The all-or-nothing check on `customer_order` (V28) already allows a whole group to be NULL, so no schema change is
needed there. `OrderView.shipping` is `null` for scrubbed orders, as for orders from before addresses. Orders and
ratings of a soft-deleted customer show the tombstone as their customer, and audit entries show it as their actor.
For staff and admin accounts only `audit` has anything to say; the other policies find nothing and do nothing.

## 3. Writers can't race the delete

Every write that creates customer-owned data first calls `Accounts.existsAndLockAgainstDeletion(username)`: cart
changes and checkout (`ordering`), rating a book (`rating`), saving an address (`customer`). A delete takes the row
`FOR UPDATE`, so it waits for a writer in flight, and a writer that comes after the delete finds no live account and
is refused with `409` ("The account no longer exists"). So no order, rating, cart or address can appear once the
delete has decided whether the customer holds records, and none is left under the old username afterwards. Reading
is not locked.

Audit entries are written later, by a listener after the publishing transaction commits, with the `actor` captured
at publish time, so a staff member's in-flight change can reach the log after their account was deleted. When the
listener records an entry whose `actor` has no live account (`Accounts.exists(username)`, a plain read added to the
public `Accounts` interface), it stores the generic actor `~deleted` instead. Only a delete followed by a new
registration of the same name inside that few-millisecond delay could still misattribute an entry; that is accepted.

## 4. Module boundaries and docs

`ordering`, `rating`, `customer` and `audit` depend on `security` for its public types, never the reverse
(`security` still depends on `shared` only). `ModularityTests` must stay green. The README (accounts section: hard vs
soft delete, the tombstone, sign-in refused, module dependency diagram), `CLAUDE.md` (a line about
`AccountDeletionPolicy` next to `BookDeletionPolicy`) and `docs/design/orders.md` (scrubbed shipping address) are
updated.

## 5. Testing

- **Per module** (`@ApplicationModuleTest`): `holdsRecords`, `removePersonalData` and `anonymize` of each policy
  against real tables (orders keep invoice and lose shipping; ratings keep their stars; audit actors change).
- **Integration, over HTTP, all modules:**
  - hard delete: a customer with cart and saved address and nothing else is deleted; the same username registers
    again and sees an empty cart, no addresses and no rating;
  - soft delete: a customer with an order and a rating is deleted; sign-in fails; the old username registers again
    as a clean account; the old orders show the tombstone, `shipping` null, `invoice` still present; the rating still
    counts in the book's average; cart and saved addresses are gone; the account is absent from `GET /api/accounts`;
  - staff with no audit entries is hard-deleted; staff with audit entries is soft-deleted: sign-in fails, the same
    username registers again and sees an empty audit trail attributed to it (old entries show the tombstone);
  - the last-admin and own-account refusals still work, and a soft-deleted admin doesn't count as an admin;
  - a deleted user's old credentials: a request after deletion is `401`.
- **Races** (like `CheckoutDeleteRaceTest`): a delete started while a checkout holds the account lock waits and then
  sees the new order (soft delete); a rating or checkout started after the delete is refused with `409`; an audit
  entry whose actor was deleted before it was recorded is stored with the actor `~deleted`.
