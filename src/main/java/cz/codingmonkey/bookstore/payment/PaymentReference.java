package cz.codingmonkey.bookstore.payment;

import com.fasterxml.jackson.annotation.JsonValue;
import cz.codingmonkey.bookstore.shared.LongId;

/**
 * Identifies a payment. Chosen by whoever asks for it (e.g. the ordering module uses the order id), so repeated
 * requests for the same payment are recognised.
 */
public record PaymentReference(@JsonValue long value) implements LongId {

    public PaymentReference {
        LongId.requirePositive(value, "Payment reference");
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
