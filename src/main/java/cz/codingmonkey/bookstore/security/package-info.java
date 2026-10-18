/**
 * Security module: staff and customer accounts (stored in the database, passwords hashed) and the access rules
 * for every endpoint. Requests authenticate with HTTP Basic; anonymous visitors can browse. Deleting an account
 * lets other modules take part through {@link AccountDeletionPolicy}; {@link Accounts} resolves usernames.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Security")
package cz.codingmonkey.bookstore.security;
