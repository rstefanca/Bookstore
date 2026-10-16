package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.inventory.ReservationId;
import cz.codingmonkey.bookstore.inventory.StockReleaseRequested;
import cz.codingmonkey.bookstore.inventory.StockReleased;
import cz.codingmonkey.bookstore.inventory.StockReservationFailed;
import cz.codingmonkey.bookstore.inventory.StockReserved;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.ordering.OrderProgressedEvent;
import cz.codingmonkey.bookstore.ordering.OrderStep;
import cz.codingmonkey.bookstore.payment.PaymentFailed;
import cz.codingmonkey.bookstore.payment.PaymentReference;
import cz.codingmonkey.bookstore.payment.PaymentRefunded;
import cz.codingmonkey.bookstore.payment.PaymentRequested;
import cz.codingmonkey.bookstore.payment.PaymentSucceeded;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Takes orders through the process: stock reservation, then payment, undoing the reservation if payment fails.
 * <p>
 * Each step reacts to the previous step's result event, asynchronously after it committed, tracked in the event
 * registry. Results may arrive twice or late: the order only moves on from the state a step expects, so anything
 * else is ignored. The inventory and payment modules identify requests by the order id (reservation id, payment
 * reference), which makes their answers repeatable too.
 */
@Component
class OrderProcess {

    private static final Logger log = LogManager.getLogger(OrderProcess.class);
    private static final ChangeLog changes = ChangeLog.of(OrderProcess.class);

    private final OrderRepository orders;
    private final ApplicationEventPublisher events;

    OrderProcess(OrderRepository orders, ApplicationEventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    @ApplicationModuleListener
    void on(StockReserved event) {
        withOrder(event.reservationId().value(), order -> {
            if (order.stockReserved()) {
                progress(order, OrderStep.STOCK_RESERVED, "Stock reserved");
                events.publishEvent(new PaymentRequested(new PaymentReference(order.getId().value()),
                        order.getTotal(), order.getCustomer()));
                changes.info("Order {}: stock reserved, awaiting payment", order.getId());
            }
        });
    }

    @ApplicationModuleListener
    void on(StockReservationFailed event) {
        withOrder(event.reservationId().value(), order -> {
            Set<BookId> missing = Set.copyOf(event.missingBooks());
            String titles = order.getLines().stream()
                    .filter(line -> missing.contains(line.getBookId()))
                    .map(OrderLine::getTitle)
                    .collect(Collectors.joining(", "));
            String why = "Not enough stock: " + titles;
            if (order.stockUnavailable(why)) {
                progress(order, OrderStep.STOCK_MISSING, why);
                progress(order, OrderStep.REJECTED, why);
                changes.info("Order {}: rejected, not enough stock", order.getId());
            }
        });
    }

    @ApplicationModuleListener
    void on(PaymentSucceeded event) {
        withOrder(event.reference().value(), order -> {
            if (order.paid()) {
                progress(order, OrderStep.PAYMENT_CHARGED, "Charged " + order.getTotal());
                progress(order, OrderStep.CONFIRMED, "Confirmed");
                changes.info("Order {}: paid, confirmed", order.getId());
            }
        });
    }

    /** Payment failed: reject the order and put its stock back. */
    @ApplicationModuleListener
    void on(PaymentFailed event) {
        withOrder(event.reference().value(), order -> {
            if (order.paymentFailed("Payment failed: " + event.reason())) {
                progress(order, OrderStep.PAYMENT_DECLINED, event.reason());
                progress(order, OrderStep.REJECTED, event.reason());
                events.publishEvent(new StockReleaseRequested(new ReservationId(order.getId().value()),
                        order.getCustomer()));
                changes.info("Order {}: rejected, payment failed; releasing stock", order.getId());
            }
        });
    }

    /** Undo steps don't change the order's state; inventory announces a release only once. */
    @ApplicationModuleListener
    void on(StockReleased event) {
        withOrder(event.reservationId().value(), order -> progress(order, OrderStep.STOCK_RELEASED, "Stock put back"));
    }

    /** Payment announces a refund only once. */
    @ApplicationModuleListener
    void on(PaymentRefunded event) {
        withOrder(event.reference().value(),
                order -> progress(order, OrderStep.PAYMENT_REFUNDED, "Refunded " + order.getTotal()));
    }

    private void progress(CustomerOrder order, OrderStep step, String detail) {
        events.publishEvent(new OrderProgressedEvent(order.getId(), step, detail, order.getCustomer(),
                StepClock.next()));
    }

    private void withOrder(long id, Consumer<CustomerOrder> step) {
        orders.findById(new OrderId(id)).ifPresentOrElse(step,
                () -> log.warn("Order process: no order {}; ignoring the event", id));
    }
}
