package cz.codingmonkey.bookstore.security;

import com.fasterxml.jackson.annotation.JsonValue;

public record AccountId(@JsonValue long value) {

    public AccountId {
        if (value <= 0) {
            throw new IllegalArgumentException("Account id must be positive, was " + value);
        }
    }

    /** Lets Spring MVC bind path variables. */
    public static AccountId of(String value) {
        return new AccountId(Long.parseLong(value));
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
