package cz.codingmonkey.bookstore.ordering;

/** A step in an order's life, as reported by {@link OrderProgressedEvent}. */
public enum OrderStep {
    PLACED, STOCK_RESERVED, STOCK_MISSING, PAYMENT_CHARGED, PAYMENT_DECLINED, CONFIRMED, REJECTED, CANCELLED,
    STOCK_RELEASED, PAYMENT_REFUNDED
}
