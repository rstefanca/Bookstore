package cz.codingmonkey.bookstore.bookcatalog;

import com.fasterxml.jackson.annotation.JsonValue;
import cz.codingmonkey.bookstore.shared.LongId;

/**
 * Identifies a book across all modules. Serialized to JSON as a plain number.
 */
public record BookId(@JsonValue long value) implements LongId {

    public BookId {
        LongId.requirePositive(value, "Book id");
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
