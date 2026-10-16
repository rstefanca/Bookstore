package cz.codingmonkey.bookstore.payment;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies a payment. Chosen by whoever asks for it (e.g. the ordering module uses the order id), so repeated
 * requests for the same payment are recognised.
 */
public record PaymentReference(@JsonValue long value) {

    public PaymentReference {
        if (value <= 0) {
            throw new IllegalArgumentException("Payment reference must be positive, was " + value);
        }
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
