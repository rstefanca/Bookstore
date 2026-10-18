package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.AccountPrincipal;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** The signed-in principal: Spring's {@link User} plus the id of the account it was loaded from. */
class AccountUser extends User implements AccountPrincipal {

    private final AccountId accountId;

    AccountUser(AccountId accountId, String username, String password,
            Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        this.accountId = accountId;
    }

    @Override
    public AccountId accountId() {
        return accountId;
    }
}
