package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.AccountIdType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.Type;
import org.jspecify.annotations.Nullable;

/**
 * A staff account. The password is only ever stored as a hash.
 */
@Entity
class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Type(AccountIdType.class)
    private AccountId id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    /** {@code null} only for accounts created before names were introduced. */
    @Column(length = 100)
    private @Nullable String firstName;

    @Column(length = 100)
    private @Nullable String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** Set when the account was soft-deleted: other modules still keep records of it. */
    private @Nullable Instant deletedAt;

    protected Account() {
    }

    Account(String username, @Nullable String firstName, @Nullable String lastName, String passwordHash, Role role) {
        this.username = username;
        this.firstName = firstName;
        this.lastName = lastName;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    AccountId getId() { return id; }
    String getUsername() { return username; }
    String getPasswordHash() { return passwordHash; }
    @Nullable String getFirstName() { return firstName; }
    @Nullable String getLastName() { return lastName; }
    Role getRole() { return role; }
    @Nullable Instant getDeletedAt() { return deletedAt; }

    void rename(@Nullable String firstName, @Nullable String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** Turns the account into an anonymous tombstone that nobody can sign in to. */
    void anonymize(String tombstoneUsername, String unusablePasswordHash, Instant deletedAt) {
        this.username = tombstoneUsername;
        this.firstName = null;
        this.lastName = null;
        this.passwordHash = unusablePasswordHash;
        this.deletedAt = deletedAt;
    }
}
