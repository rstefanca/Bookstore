package cz.codingmonkey.bookstore.customer.internal;

import cz.codingmonkey.bookstore.security.AccountDeletionPolicy;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Saved addresses never outlive the account (orders keep their own copies). */
@Component
class CustomerAccountDeletion implements AccountDeletionPolicy {

    private static final ChangeLog changes = ChangeLog.of(CustomerAccountDeletion.class);

    private final SavedAddressesRepository addresses;

    CustomerAccountDeletion(SavedAddressesRepository addresses) {
        this.addresses = addresses;
    }

    @Override
    public boolean holdsRecords(AccountId account) {
        return false;
    }

    @Override
    @Transactional
    public void removePersonalData(AccountId account) {
        if (addresses.deleteByCustomer(account.value()) > 0) {
            changes.info("Removed the saved addresses of account {}", account);
        }
    }

    @Override
    public void anonymize(AccountId account) {
    }
}
