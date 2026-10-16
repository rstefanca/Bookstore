package cz.codingmonkey.bookstore.inventory;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a stock reservation. Chosen by whoever asks for it (e.g. the ordering module uses the order id), so
 * repeated requests for the same reservation are recognised.
 */
public record ReservationId(@JsonValue long value) {

    public ReservationId {
        if (value <= 0) {
            throw new IllegalArgumentException("Reservation id must be positive, was " + value);
        }
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
