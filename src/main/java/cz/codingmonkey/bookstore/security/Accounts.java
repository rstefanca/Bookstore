package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.shared.AccountId;
import java.util.Collection;
import java.util.Map;

/** Read access to accounts for other modules. */
public interface Accounts {

    /** Shown instead of the username of a soft-deleted or unknown account. */
    String DELETED_USER = "(deleted user)";

    /**
     * Usernames to show for the given accounts. Every requested id is in the result; soft-deleted and unknown
     * accounts map to {@link #DELETED_USER}.
     */
    Map<AccountId, String> usernamesOf(Collection<AccountId> accounts);
}
