package cz.codingmonkey.bookstore.bookcatalog;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a book across all modules. Serialized to JSON as a plain number.
 */
public record BookId(@JsonValue long value) {

    public BookId {
        if (value <= 0) {
            throw new IllegalArgumentException("Book id must be positive, was " + value);
        }
    }

    /** Lets Spring MVC bind path variables and request parameters. */
    public static BookId of(String value) {
        return new BookId(Long.parseLong(value));
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
