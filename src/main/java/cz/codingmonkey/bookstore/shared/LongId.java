package cz.codingmonkey.bookstore.shared;

/**
 * Common supertype of the typed ids: immutable value objects (records) wrapping a {@code long}. Persisted through a
 * {@link LongIdType} subclass. Whether non-positive values are rejected is up to each id; those bound from HTTP do so
 * via {@link #requirePositive(long, String)}.
 */
public interface LongId {

    long value();

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
