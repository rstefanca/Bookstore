package cz.codingmonkey.bookstore.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.Type;

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
    private String firstName;

    @Column(length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    protected Account() {
    }

    Account(String username, String firstName, String lastName, String passwordHash, Role role) {
        this.username = username;
        this.firstName = firstName;
        this.lastName = lastName;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    AccountId getId() { return id; }
    String getUsername() { return username; }
    String getPasswordHash() { return passwordHash; }
    String getFirstName() { return firstName; }
    String getLastName() { return lastName; }
    Role getRole() { return role; }

    void rename(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
