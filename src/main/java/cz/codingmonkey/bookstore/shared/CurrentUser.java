package cz.codingmonkey.bookstore.shared;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is making the current change, for recording in events (and from there in the audit log).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * The signed-in user's username, for display and logging only (use {@link #id()} to identify the account), or {@code null} when nobody is signed in on this thread: anonymous requests,
     * startup, or background work such as event listeners (which should pass on the actor of the event that
     * triggered them instead).
     */
    public static @Nullable String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    /**
     * The signed-in account's id, or {@code null} when nobody is signed in, the request is anonymous, or the
     * principal doesn't carry an account id.
     */
    public static @Nullable AccountId id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getPrincipal() instanceof AccountPrincipal principal ? principal.accountId() : null;
    }

    /** The signed-in account's id; for code behind rules that guarantee a user. */
    public static AccountId requireId() {
        AccountId id = id();
        if (id == null) {
            throw new IllegalStateException("No signed-in account");
        }
        return id;
    }
}
