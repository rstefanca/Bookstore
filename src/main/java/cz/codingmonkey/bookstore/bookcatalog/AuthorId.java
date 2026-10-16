package cz.codingmonkey.bookstore.bookcatalog;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies an author across all modules. Serialized to JSON as a plain number.
 */
public record AuthorId(@JsonValue long value) {

    public AuthorId {
        if (value <= 0) {
            throw new IllegalArgumentException("Author id must be positive, was " + value);
        }
    }

    /** Lets Spring MVC bind path variables and request parameters. */
    public static AuthorId of(String value) {
        return new AuthorId(Long.parseLong(value));
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
