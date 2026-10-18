package cz.codingmonkey.bookstore.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void idIsNullWithoutAuthentication() {
        assertThat(CurrentUser.id()).isNull();
    }

    @Test
    void idIsNullForAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(CurrentUser.id()).isNull();
    }

    @Test
    void idComesFromTheAccountPrincipal() {
        AccountPrincipal principal = () -> new AccountId(42);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));

        assertThat(CurrentUser.id()).isEqualTo(new AccountId(42));
        assertThat(CurrentUser.requireId()).isEqualTo(new AccountId(42));
    }

    @Test
    void requireIdThrowsWithoutAUser() {
        assertThatThrownBy(CurrentUser::requireId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void usernameStillWorks() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_CUSTOMER")));

        assertThat(CurrentUser.username()).isEqualTo("alice");
    }
}
