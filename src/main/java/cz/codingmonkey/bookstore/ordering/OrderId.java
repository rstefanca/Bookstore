package cz.codingmonkey.bookstore.ordering;

import com.fasterxml.jackson.annotation.JsonValue;
import cz.codingmonkey.bookstore.shared.LongId;

/**
 * Identifies an order. Serialized to JSON as a plain number.
 */
public record OrderId(@JsonValue long value) implements LongId {

    public OrderId {
        LongId.requirePositive(value, "Order id");
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
