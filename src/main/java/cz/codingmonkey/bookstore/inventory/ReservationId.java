package cz.codingmonkey.bookstore.inventory;

import com.fasterxml.jackson.annotation.JsonValue;
import cz.codingmonkey.bookstore.shared.LongId;

/**
 * Identifies a stock reservation. Chosen by whoever asks for it (e.g. the ordering module uses the order id), so
 * repeated requests for the same reservation are recognised.
 */
public record ReservationId(@JsonValue long value) implements LongId {

    public ReservationId {
        LongId.requirePositive(value, "Reservation id");
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
