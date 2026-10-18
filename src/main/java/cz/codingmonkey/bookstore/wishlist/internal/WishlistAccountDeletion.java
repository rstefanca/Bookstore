package cz.codingmonkey.bookstore.wishlist.internal;

import cz.codingmonkey.bookstore.security.AccountDeletionPolicy;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import org.springframework.stereotype.Component;

/**
 * A wishlist is not a record to keep: it goes with its customer's account, and never makes a deletion a soft one.
 */
@Component
class WishlistAccountDeletion implements AccountDeletionPolicy {

    private static final ChangeLog changes = ChangeLog.of(WishlistAccountDeletion.class);

    private final WishlistItems items;

    WishlistAccountDeletion(WishlistItems items) {
        this.items = items;
    }

    @Override
    public boolean holdsRecords(AccountId account) {
        return false;
    }

    @Override
    public void removePersonalData(AccountId account) {
        int removed = items.deleteAll(account);
        if (removed > 0) {
            changes.info("Removed {} wishlist entries of deleted account {}", removed, account);
        }
    }

    @Override
    public void anonymize(AccountId account) {
    }
}
