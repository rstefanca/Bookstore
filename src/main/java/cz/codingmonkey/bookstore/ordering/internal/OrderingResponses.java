package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.pricing.Money;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Response bodies of the cart and order API. */
final class OrderingResponses {

    private OrderingResponses() {
    }

    /**
     * A book in the cart.
     *
     * @param title     {@code null} if the book no longer exists
     * @param unitPrice current price; {@code null} if the book is no longer for sale (checkout will refuse it)
     * @param lineTotal {@code null} likewise
     */
    record CartItem(BookId bookId, @Nullable String title, int quantity, @Nullable Money unitPrice,
            @Nullable Money lineTotal) {
    }

    /**
     * The customer's cart.
     *
     * @param total of the items that are for sale; {@code null} when none is (e.g. an empty cart)
     */
    record CartView(List<CartItem> items, @Nullable Money total) {
    }

    /**
     * A book in an order, with the title and price it had at checkout.
     *
     * @param unitPrice price of one copy
     */
    record OrderLineView(BookId bookId, String title, int quantity, Money unitPrice) {
    }

    /**
     * An order.
     *
     * @param customer  the customer's username
     * @param status    {@code PENDING}, {@code AWAITING_PAYMENT}, {@code CONFIRMED}, {@code REJECTED} or
     *                  {@code CANCELLED}
     * @param reason    why the order was rejected, otherwise {@code null}
     * @param createdAt when the order was placed
     */
    record OrderView(
            OrderId id,
            String customer,
            String status,
            List<OrderLineView> lines,
            Money total,
            @Nullable String reason,
            Instant createdAt
    ) {

        static OrderView from(CustomerOrder order) {
            var currency = order.getTotal().currency();
            return new OrderView(order.getId(), order.getCustomer(), order.getStatus().name(),
                    order.getLines().stream().map(line -> new OrderLineView(line.getBookId(), line.getTitle(),
                            line.getQuantity(), new Money(line.getUnitPrice(), currency))).toList(),
                    order.getTotal(), order.getReason(), order.getCreatedAt());
        }
    }
}
