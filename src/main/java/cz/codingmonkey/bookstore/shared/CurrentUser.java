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
     * The signed-in user's username, or {@code null} when nobody is signed in on this thread: anonymous requests,
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
}
