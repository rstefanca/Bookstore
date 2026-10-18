package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.shared.AccountId;

/** Lets modules take part in deleting an account; consulted inside the deleting transaction. */
public interface AccountDeletionPolicy {

    /** Whether this module keeps records of the account that must survive the deletion (orders, ratings, audit). */
    boolean holdsRecords(AccountId account);

    /** Removes what must not outlive the account (cart, saved addresses). Called for every deletion. */
    void removePersonalData(AccountId account);

    /** Removes personal data from the records this module keeps; called only for a soft delete. */
    void anonymize(AccountId account);
}
