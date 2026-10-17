package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.ordering.OrderIdType;
import cz.codingmonkey.bookstore.pricing.Money;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.hibernate.annotations.Type;

/**
 * An order and where it is in the order process ({@link OrderStatus}). Each step method moves it on only from the
 * state it expects and returns whether it did, so late or repeated events are ignored. The undo steps (stock
 * released, payment refunded) don't change the state; flags record that they were seen.
 */
@Entity
@Table(name = "customer_order")
class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Type(OrderIdType.class)
    private OrderId id;

    /** The customer's username. */
    @Column(nullable = false, length = 100)
    private String customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @ElementCollection
    @CollectionTable(name = "order_line", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "position")
    private List<OrderLine> lines = new ArrayList<>();

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    private Currency currency;

    /** Why it was rejected. */
    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    /** The undo steps seen so far; set only through {@link OrderRepository}, without a version check. */
    @Column(nullable = false)
    private boolean stockReleased;

    @Column(nullable = false)
    private boolean paymentRefunded;

    @Version
    private long version;

    protected CustomerOrder() {
    }

    CustomerOrder(String customer, List<OrderLine> lines, Currency currency) {
        this.customer = customer;
        this.lines.addAll(lines);
        this.currency = currency;
        this.total = lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
    }

    OrderId getId() { return id; }
    String getCustomer() { return customer; }
    OrderStatus getStatus() { return status; }
    List<OrderLine> getLines() { return List.copyOf(lines); }
    Money getTotal() { return new Money(total, currency); }
    String getReason() { return reason; }
    Instant getCreatedAt() { return createdAt; }

    boolean stockReserved() {
        return moveOn(OrderStatus.PENDING, OrderStatus.AWAITING_PAYMENT, null);
    }

    boolean stockUnavailable(String why) {
        return moveOn(OrderStatus.PENDING, OrderStatus.REJECTED, why);
    }

    boolean paid() {
        return moveOn(OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED, null);
    }

    boolean paymentFailed(String why) {
        return moveOn(OrderStatus.AWAITING_PAYMENT, OrderStatus.REJECTED, why);
    }

    /** Only a confirmed order can be cancelled; while it's still being processed, or once rejected, it can't. */
    void cancel() {
        if (!moveOn(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, null)) {
            throw new OrderNotCancellableException(id, status);
        }
    }

    private boolean moveOn(OrderStatus from, OrderStatus to, String why) {
        if (status != from) {
            return false;
        }
        status = to;
        reason = why;
        return true;
    }
}
