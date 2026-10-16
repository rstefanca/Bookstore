package cz.codingmonkey.bookstore.ordering;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies an order. Serialized to JSON as a plain number.
 */
public record OrderId(@JsonValue long value) {

    public OrderId {
        if (value <= 0) {
            throw new IllegalArgumentException("Order id must be positive, was " + value);
        }
    }

    /** Lets Spring MVC bind path variables. */
    public static OrderId of(String value) {
        return new OrderId(Long.parseLong(value));
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
