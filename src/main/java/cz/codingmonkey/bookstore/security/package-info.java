/**
 * Security module: staff and customer accounts (stored in the database, passwords hashed) and the access rules
 * for every endpoint. Requests authenticate with HTTP Basic; anonymous visitors can browse. Other modules don't
 * depend on it.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Security")
package cz.codingmonkey.bookstore.security;
