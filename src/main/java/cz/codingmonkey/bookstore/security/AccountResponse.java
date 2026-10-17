package cz.codingmonkey.bookstore.security;

import org.jspecify.annotations.Nullable;

/**
 * @param firstName {@code null} only for accounts created before names were introduced
 * @param lastName  likewise
 */
record AccountResponse(AccountId id, String username, @Nullable String firstName, @Nullable String lastName,
        Role role) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getUsername(), account.getFirstName(),
                account.getLastName(), account.getRole());
    }
}
