package cz.codingmonkey.bookstore.customer.internal;

import cz.codingmonkey.bookstore.customer.Address;
import org.jspecify.annotations.Nullable;

/**
 * A customer's saved addresses; each may be {@code null}.
 */
record SavedAddressesView(@Nullable Address shipping, @Nullable Address invoice) {
}
