package cz.codingmonkey.bookstore.bookcatalog;

import com.fasterxml.jackson.annotation.JsonValue;
import cz.codingmonkey.bookstore.shared.LongId;

/**
 * Identifies a genre across all modules. Serialized to JSON as a plain number.
 */
public record GenreId(@JsonValue long value) implements LongId {

    public GenreId {
        LongId.requirePositive(value, "Genre id");
    }

    /** Lets Spring MVC bind path variables and request parameters. */
    public static GenreId of(String value) {
        return new GenreId(Long.parseLong(value));
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
