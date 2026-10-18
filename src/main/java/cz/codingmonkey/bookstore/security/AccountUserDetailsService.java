package cz.codingmonkey.bookstore.security;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lets Spring Security check credentials against the stored accounts. */
@Service
class AccountUserDetailsService implements UserDetailsService {

    private final AccountRepository accounts;

    AccountUserDetailsService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return accounts.findByUsernameIgnoreCaseAndDeletedAtIsNull(username)
                .map(account -> new AccountUser(account.getId(), account.getUsername(), account.getPasswordHash(),
                        AuthorityUtils.createAuthorityList("ROLE_" + account.getRole().name())))
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }
}
