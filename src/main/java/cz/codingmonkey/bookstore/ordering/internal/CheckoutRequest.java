package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.customer.Address;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;

/**
 * The optional body of a checkout. An address sent here applies to this order only; it isn't saved. An address left
 * out is taken from the customer's saved one.
 *
 * @param shipping where to send the books; {@code null} to use the saved shipping address
 * @param invoice  where to send the invoice; {@code null} to use the saved invoice address
 */
record CheckoutRequest(@Nullable @Valid Address shipping, @Nullable @Valid Address invoice) {

    /** Nothing sent: both addresses come from the saved ones. */
    static final CheckoutRequest NONE = new CheckoutRequest(null, null);
}
