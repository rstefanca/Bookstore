package cz.codingmonkey.bookstore.shared;

import java.util.Locale;

/**
 * Common supertype of the typed ids: immutable value objects (records) wrapping a {@code long}. Persisted through a
 * {@link LongIdType} subclass. Whether non-positive values are rejected is up to each id; those bound from HTTP do so
 * via {@link #requirePositive(long, String)}.
 */
public interface LongId {

    long value();

    /**
     * Name of this id's type, e.g. "BOOK" for BookId: the class name without a trailing "Id", in upper snake case.
     * Stored in the audit log, so renaming an id class changes it (TypeNamesTest pins the stored names).
     */
    default String typeName() {
        String name = getClass().getSimpleName();
        if (name.endsWith("Id")) {
            name = name.substring(0, name.length() - 2);
        }
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    /**
     * Rejects ids that are zero or negative, for use in a record's compact constructor.
     *
     * @param what how the id is named in the error message, e.g. {@code "Book id"}
     */
    static void requirePositive(long value, String what) {
        if (value <= 0) {
            throw new IllegalArgumentException(what + " must be positive, was " + value);
        }
    }
}
