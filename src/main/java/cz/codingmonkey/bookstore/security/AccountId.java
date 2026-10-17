package cz.codingmonkey.bookstore.security;

import com.fasterxml.jackson.annotation.JsonValue;
import cz.codingmonkey.bookstore.shared.LongId;

public record AccountId(@JsonValue long value) implements LongId {

    public AccountId {
        LongId.requirePositive(value, "Account id");
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
