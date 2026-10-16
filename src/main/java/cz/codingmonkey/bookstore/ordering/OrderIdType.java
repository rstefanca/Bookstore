package cz.codingmonkey.bookstore.ordering;

import cz.codingmonkey.bookstore.shared.LongIdType;

/**
 * Persists {@link OrderId} as {@code BIGINT}. Referenced via {@code @Type(OrderIdType.class)} by every entity that
 * stores an order id, including those in other modules.
 */
public class OrderIdType extends LongIdType<OrderId> {

    public OrderIdType() {
        super(OrderId.class, OrderId::new, OrderId::value);
    }
}
