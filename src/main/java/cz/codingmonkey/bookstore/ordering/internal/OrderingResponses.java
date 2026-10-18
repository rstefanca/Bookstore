package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.customer.Address;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
     * @param customer   the customer's username, {@code "(deleted user)"} if their account was deleted
     * @param customerId the customer's account id
     * @param status     {@code PENDING}, {@code AWAITING_PAYMENT}, {@code CONFIRMED}, {@code REJECTED} or
     *                   {@code CANCELLED}
     * @param reason     why the order was rejected, otherwise {@code null}
     * @param createdAt  when the order was placed
     * @param shipping   where the books go; {@code null} in the staff list and for orders placed before addresses
     *                   were kept
     * @param invoice    where the invoice goes; {@code null} likewise
     */
    record OrderView(
            OrderId id,
            String customer,
            AccountId customerId,
            String status,
            List<OrderLineView> lines,
            Money total,
            @Nullable String reason,
            Instant createdAt,
            @Nullable Address shipping,
            @Nullable Address invoice
    ) {

        /** @param usernames the username of the order's customer, from {@code Accounts.usernamesOf} */
        static OrderView from(CustomerOrder order, Map<AccountId, String> usernames) {
            return of(order, usernames, order.getShipping(), order.getInvoice());
        }

        /** For the staff list, which doesn't show customers' addresses. */
        static OrderView withoutAddresses(CustomerOrder order, Map<AccountId, String> usernames) {
            return of(order, usernames, null, null);
        }

        private static OrderView of(CustomerOrder order, Map<AccountId, String> usernames,
                @Nullable Address shipping, @Nullable Address invoice) {
            var currency = order.getTotal().currency();
            String customer = usernames.get(order.getCustomer());
            if (customer == null) {
                throw new IllegalArgumentException("No username for account " + order.getCustomer());
            }
            return new OrderView(order.getId(), customer, order.getCustomer(), order.getStatus().name(),
                    order.getLines().stream().map(line -> new OrderLineView(line.getBookId(), line.getTitle(),
                            line.getQuantity(), new Money(line.getUnitPrice(), currency))).toList(),
                    order.getTotal(), order.getReason(), order.getCreatedAt(), shipping, invoice);
        }
    }
}
