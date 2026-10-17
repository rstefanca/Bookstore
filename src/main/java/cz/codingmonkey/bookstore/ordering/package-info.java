/**
 * Ordering module: customers' carts and orders. Checkout turns the cart into an order, which the
 * {@code OrderProcess} takes through stock reservation (inventory) and payment (payment module) using their
 * request/result events, undoing earlier steps when a later one fails. Inventory and payment don't know about
 * orders; dependencies only go from here to them.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Ordering")
package cz.codingmonkey.bookstore.ordering;
