/**
 * Customer module. Holds a customer's saved shipping and invoice addresses and offers them to other modules through
 * {@link cz.codingmonkey.bookstore.customer.CustomerAddresses}. Customers are referenced by account id.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Customers")
package cz.codingmonkey.bookstore.customer;
