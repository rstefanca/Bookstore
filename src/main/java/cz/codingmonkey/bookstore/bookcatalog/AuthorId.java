package cz.codingmonkey.bookstore.bookcatalog;

import com.fasterxml.jackson.annotation.JsonValue;
import cz.codingmonkey.bookstore.shared.LongId;

/**
 * Identifies an author across all modules. Serialized to JSON as a plain number.
 */
public record AuthorId(@JsonValue long value) implements LongId {

    public AuthorId {
        LongId.requirePositive(value, "Author id");
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
