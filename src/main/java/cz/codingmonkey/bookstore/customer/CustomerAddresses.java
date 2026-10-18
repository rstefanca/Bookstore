package cz.codingmonkey.bookstore.customer;

import cz.codingmonkey.bookstore.shared.AccountId;
import java.util.Optional;

/**
 * Read access to customers' saved addresses for other modules.
 */
public interface CustomerAddresses {

    Optional<Address> getShippingAddress(AccountId customer);

    Optional<Address> getInvoiceAddress(AccountId customer);
}
