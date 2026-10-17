package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookDeletionPolicy;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Prevents deleting a book that was sold or is being ordered. Rejected and cancelled orders don't count.
 * Checkout locks its books against deletion, so a delete can't slip in between this check and a new order.
 */
@Component
class SoldBookDeletionPolicy implements BookDeletionPolicy {

    private static final Set<OrderStatus> KEEPING_BOOKS =
            EnumSet.of(OrderStatus.PENDING, OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED);

    private final OrderRepository orders;

    SoldBookDeletionPolicy(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Optional<String> vetoReason(BookId bookId) {
        return orders.existsByLinesBookIdAndStatusIn(bookId, KEEPING_BOOKS)
                ? Optional.of("Book %s has orders; books that were sold or are being ordered can't be deleted"
                        .formatted(bookId))
                : Optional.empty();
    }
}
