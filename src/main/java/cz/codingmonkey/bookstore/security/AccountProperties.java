package cz.codingmonkey.bookstore.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param initialAdmin the admin account created at startup when no account exists yet
 */
@ConfigurationProperties("bookstore.security")
record AccountProperties(@DefaultValue InitialAdmin initialAdmin) {

    /**
     * @param password  optional; if not set, a random password is generated and logged once
     * @param firstName optional; can be set later with {@code PUT /api/accounts/me}
     * @param lastName  optional, likewise
     */
    record InitialAdmin(@DefaultValue("admin") String username, String password, String firstName,
            String lastName) {
    }
}
