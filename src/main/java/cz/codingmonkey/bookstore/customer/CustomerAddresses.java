package cz.codingmonkey.bookstore.customer;

import java.util.Optional;

/**
 * Read access to customers' saved addresses for other modules.
 */
public interface CustomerAddresses {

    Optional<Address> shipping(String username);

    Optional<Address> invoice(String username);
}
