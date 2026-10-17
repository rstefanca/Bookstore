package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.ordering.OrderId;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface OrderRepository extends JpaRepository<CustomerOrder, OrderId> {

    Page<CustomerOrder> findByCustomer(String customer, Pageable page);

    Optional<CustomerOrder> findByIdAndCustomer(OrderId id, String customer);

    Page<CustomerOrder> findAllBy(Pageable page);

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
