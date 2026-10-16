package cz.codingmonkey.bookstore.ordering.internal;

/**
 * <pre>
 * PENDING --stock reserved--> AWAITING_PAYMENT --paid--> CONFIRMED --cancelled--> CANCELLED
 *    |                            |
 *    +--no stock--> REJECTED      +--payment failed--> REJECTED
 * </pre>
 */
enum OrderStatus {
    PENDING, AWAITING_PAYMENT, CONFIRMED, REJECTED, CANCELLED
}
