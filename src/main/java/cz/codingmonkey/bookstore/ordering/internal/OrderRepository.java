package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.ordering.OrderId;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<CustomerOrder, OrderId> {

    Page<CustomerOrder> findByCustomer(String customer, Pageable page);

    Optional<CustomerOrder> findByIdAndCustomer(OrderId id, String customer);

    Page<CustomerOrder> findAllBy(Pageable page);
}
