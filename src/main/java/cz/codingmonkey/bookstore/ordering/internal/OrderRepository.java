package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderRepository extends JpaRepository<CustomerOrder, OrderId> {

    Page<CustomerOrder> findByCustomer(AccountId customer, Pageable page);

    Optional<CustomerOrder> findByIdAndCustomer(OrderId id, AccountId customer);

    Page<CustomerOrder> findAllBy(Pageable page);

    boolean existsByCustomer(AccountId customer);

    /**
     * Sets the shipping address group of the customer's orders to NULL (the invoice stays). Doesn't touch
     * {@code version}: {@link CustomerOrder} is {@code @DynamicUpdate}, so an order step that read the order earlier
     * writes only the columns it changed and neither fails nor writes the address back.
     *
     * @return number of orders scrubbed
     */
    @Modifying
    @Query(value = """
            UPDATE customer_order
               SET shipping_name = NULL, shipping_street = NULL, shipping_city = NULL,
                   shipping_postal_code = NULL, shipping_country = NULL
             WHERE customer_id = :customer AND shipping_name IS NOT NULL
            """, nativeQuery = true)
    int scrubShipping(@Param("customer") long customer);

    /** Whether an order in one of the given states has a line for the book. */
    boolean existsByLinesBookIdAndStatusIn(BookId bookId, Collection<OrderStatus> statuses);

    /**
     * Notes that the order's stock was put back; 1 the first time, 0 after. A single conditional update, so the
     * release and the refund, handled in parallel, don't conflict on the order's version.
     */
    @Modifying
    @Query("UPDATE CustomerOrder o SET o.stockReleased = true WHERE o.id = :id AND o.stockReleased = false")
    int markStockReleased(OrderId id);

    /** Notes that the order's payment was refunded; 1 the first time, 0 after. */
    @Modifying
    @Query("UPDATE CustomerOrder o SET o.paymentRefunded = true WHERE o.id = :id AND o.paymentRefunded = false")
    int markPaymentRefunded(OrderId id);
}
