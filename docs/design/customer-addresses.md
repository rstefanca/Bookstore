# Design: customer addresses

Status: implemented (2026-10-18).

Decided while planning: field lengths are `name` 100, `street` 200, `city` 100, `postalCode` 20; `country` is two
letters and normalized to upper case (not rejected); the staff list `GET /api/orders/all` omits addresses.

## Goal

Customers need a shipping address and an invoice address. Staff and admins need neither, so addresses stay out of
`Account` (which serves every role) and out of the `security` module.

Agreed with the user:
- A customer saves **one preferred shipping address and one preferred invoice address**, both optional.
- At checkout the customer may **override either** one for that order.
- **Both addresses are required to check out**, from the request or the saved ones. Otherwise checkout fails with
  `400` before anything is reserved.
- Each order stores its **own copy** of both addresses (snapshot, like `order_line` does with titles and prices);
  later edits of the saved addresses never change existing orders.
- Only `CUSTOMER` accounts use the endpoints. Staff and admins never see or hold address data.

Not included: an address book with several addresses, validating that a postal code matches a country.
Saved addresses are deleted with the account (`docs/design/account-deletion.md`).

## 1. Module `customer`

A new Spring Modulith module `cz.codingmonkey.bookstore.customer`. It depends on `shared` and, since account deletion, `security`'s `AccountDeletionPolicy`. `ordering`
depends on it; it doesn't know orders, and `security` doesn't know it (the link is the account id).

Public API (module root package):

```java
/**
 * @param name the recipient: a person or a company
 * @param country ISO 3166-1 alpha-2, upper case
 */
public record Address(String name, String street, String city, String postalCode, String country) { }

/** Read access to customers' saved addresses for other modules. */
public interface CustomerAddresses {

    Optional<Address> shipping(AccountId customer);

    Optional<Address> invoice(AccountId customer);
}
```

All `Address` fields are required, stripped and length-limited (name, street, city 100-200; postal code 20;
country exactly two letters).

Internal (`customer.internal`): `AddressController`, `AddressService` (implements `CustomerAddresses`),
`SavedAddresses` (entity, one row per customer, keyed by the account id like `cart`; shipping and invoice are nullable
embedded `Address`es), `SavedAddressesRepository`, `SavedAddressesView` (record of both optional addresses returned by `GET`). Both packages get a
`package-info.java` with `@NullMarked`; the root one also `@ApplicationModule(displayName = "Customers")`.

## 2. Table

Migration `V27__create_customer_address.sql`: `customer_address(customer PK (since V31 `customer_id`, the account id), shipping_name, shipping_street,
shipping_city, shipping_postal_code, shipping_country, invoice_*)`. A `CHECK` per group makes it all-or-nothing, so
half an address can't be stored.

## 3. API (role `CUSTOMER` only, rules added to `SecurityConfiguration`)

| Method and path                        | Behavior                                           |
|----------------------------------------|----------------------------------------------------|
| `GET /api/me/addresses`                | `{shipping, invoice}`, each possibly `null`        |
| `PUT /api/me/addresses/shipping`       | Replaces the saved shipping address                |
| `PUT /api/me/addresses/invoice`        | Replaces the saved invoice address                 |
| `DELETE /api/me/addresses/shipping`    | Removes it (also when none is saved)               |
| `DELETE /api/me/addresses/invoice`     | Removes it (also when none is saved)               |

Follows the usual conventions: Javadoc and `@ApiResponse` for OpenAPI, `@Tag`, problem-detail errors. Changes go
through `ChangeLog` naming only the customer and the address type; the address is personal data and isn't logged.

## 4. Checkout

`POST /api/cart/checkout` takes an optional body `{shipping?, invoice?}`; an empty or missing body still works.
For each address the request value wins, otherwise `CartService` uses the saved one from `CustomerAddresses`. If
either is still missing, checkout throws `MissingAddressException` (`@ResponseStatus(BAD_REQUEST)`, e.g. "Shipping
address is required: send one or save one") before stock is reserved, so there is nothing to undo. An address sent
at checkout applies to that order only and is not saved.

## 5. Order snapshot

Migration `V28__add_order_addresses.sql` adds `shipping_*` and `invoice_*` columns to `customer_order`, with the
same all-or-nothing `CHECK` per group. They are nullable in the schema because existing orders have no addresses;
the entity requires both for new ones. `CustomerOrder` takes both in its constructor and `OrderView` exposes them
(`null` for old orders). When the customer's account is soft-deleted, the shipping group of their orders is set to
NULL (invoice kept); see `docs/design/account-deletion.md`. The order state machine, events, stock, payment and audit flows don't change.

## 6. Module boundaries

`ordering` gains a dependency on `customer`; nothing points back, so `ModularityTests` should stay green. The
README module list, API table and per-module rules, and the checkout part of `docs/design/orders.md`, are updated.

## 7. Testing

- **Customer module** (HTTP integration test): get, put and delete; validation errors; `STAFF` and `ADMIN` get 403,
  anonymous gets 401; customers never see each other's addresses.
- **Checkout**: saved addresses used as defaults; request overrides; missing shipping or invoice gives 400 and
  reserves no stock; the snapshot stays unchanged after the customer edits the saved address.
- **Existing tests**: checkout and order tests need addresses, so the test customer gets saved ones in a shared
  fixture.
