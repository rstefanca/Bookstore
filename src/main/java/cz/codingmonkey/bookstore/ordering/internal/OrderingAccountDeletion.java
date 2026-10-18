package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.security.AccountDeletionPolicy;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders outlive their customer's account: an account with an order is only anonymized, which removes the shipping
 * address from its orders. The invoice address stays, as accounting needs it. The cart is always removed.
 */
@Component
class OrderingAccountDeletion implements AccountDeletionPolicy {

    private static final ChangeLog changes = ChangeLog.of(OrderingAccountDeletion.class);

    private final OrderRepository orders;
    private final CartRepository carts;

    OrderingAccountDeletion(OrderRepository orders, CartRepository carts) {
        this.orders = orders;
        this.carts = carts;
    }

    @Override
    public boolean holdsRecords(AccountId account) {
        return orders.existsByCustomer(account);
    }

    @Override
    @Transactional
    public void removePersonalData(AccountId account) {
        carts.deleteById(account);
        changes.info("Removed the cart of account {}", account);
    }

    /** Scrubs with an update that bumps the order version: an order step that read the order earlier then fails. */
    @Override
    @Transactional
    public void anonymize(AccountId account) {
        int scrubbed = orders.scrubShipping(account.value());
        changes.info("Removed the shipping address from {} orders of account {}", scrubbed, account);
    }
}
