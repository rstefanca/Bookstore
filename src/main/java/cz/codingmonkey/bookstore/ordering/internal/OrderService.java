package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.inventory.ReservationId;
import cz.codingmonkey.bookstore.inventory.StockReleaseRequested;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.ordering.OrderProgressedEvent;
import cz.codingmonkey.bookstore.ordering.OrderStep;
import cz.codingmonkey.bookstore.ordering.internal.OrderingResponses.OrderView;
import cz.codingmonkey.bookstore.payment.PaymentReference;
import cz.codingmonkey.bookstore.payment.RefundRequested;
import cz.codingmonkey.bookstore.security.Accounts;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class OrderService {

    private static final ChangeLog changes = ChangeLog.of(OrderService.class);

    private final OrderRepository orders;
    private final Accounts accounts;
    private final ApplicationEventPublisher events;

    OrderService(OrderRepository orders, Accounts accounts, ApplicationEventPublisher events) {
        this.orders = orders;
        this.accounts = accounts;
        this.events = events;
    }

    Page<OrderView> findOwn(AccountId customer, Pageable page) {
        Map<AccountId, String> usernames = accounts.usernamesOf(List.of(customer));
        return orders.findByCustomer(customer, page).map(order -> OrderView.from(order, usernames));
    }

    /** Another customer's order is reported as not found, so order ids reveal nothing. */
    OrderView findOwn(AccountId customer, OrderId id) {
        return OrderView.from(own(customer, id), accounts.usernamesOf(List.of(customer)));
    }

    /** The customers' usernames are looked up once for the whole page. */
    Page<OrderView> findAll(Pageable page) {
        Page<CustomerOrder> found = orders.findAllBy(page);
        Map<AccountId, String> usernames = accounts.usernamesOf(
                found.stream().map(CustomerOrder::getCustomer).distinct().toList());
        return found.map(order -> OrderView.withoutAddresses(order, usernames));
    }

    /** Cancels a confirmed order: its stock goes back and the payment is refunded. */
    @Transactional
    OrderView cancel(AccountId customer, OrderId id) {
        CustomerOrder order = own(customer, id);
        order.cancel();
        events.publishEvent(new StockReleaseRequested(new ReservationId(id.value()), customer));
        events.publishEvent(new RefundRequested(new PaymentReference(id.value()), customer));
        events.publishEvent(new OrderProgressedEvent(id, OrderStep.CANCELLED, "Cancelled by the customer", customer,
                StepClock.next()));
        changes.info("Order {}: cancelled; releasing stock and refunding", id);
        return OrderView.from(order, accounts.usernamesOf(List.of(customer)));
    }

    private CustomerOrder own(AccountId customer, OrderId id) {
        return orders.findByIdAndCustomer(id, customer).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
