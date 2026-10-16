package cz.codingmonkey.bookstore.bookcatalog;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a genre across all modules. Serialized to JSON as a plain number.
 */
public record GenreId(@JsonValue long value) {

    public GenreId {
        if (value <= 0) {
            throw new IllegalArgumentException("Genre id must be positive, was " + value);
        }
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
