package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.customer.Address;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.ordering.OrderIdType;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.AccountIdType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
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
import org.hibernate.annotations.EmbeddedColumnNaming;
import org.hibernate.annotations.Type;
import org.jspecify.annotations.Nullable;

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

    /** The customer's account. */
    @Column(name = "customer_id", nullable = false)
    @Type(AccountIdType.class)
    private AccountId customer;

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
    private @Nullable String reason;

    @Column(nullable = false)
    private Instant createdAt;

    /** Copied at checkout; {@code null} for orders placed before addresses existed. */
    @Embedded
    @EmbeddedColumnNaming("shipping_%s")
    private @Nullable Address shipping;

    @Embedded
    @EmbeddedColumnNaming("invoice_%s")
    private @Nullable Address invoice;

    /** The undo steps seen so far; set only through {@link OrderRepository}, without a version check. */
    @Column(nullable = false)
    private boolean stockReleased;

    @Column(nullable = false)
    private boolean paymentRefunded;

    @Version
    private long version;

    protected CustomerOrder() {
    }

    CustomerOrder(AccountId customer, List<OrderLine> lines, Currency currency, Address shipping, Address invoice) {
        this.customer = customer;
        this.lines.addAll(lines);
        this.currency = currency;
        this.shipping = shipping;
        this.invoice = invoice;
        this.total = lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
    }

    OrderId getId() { return id; }
    AccountId getCustomer() { return customer; }
    OrderStatus getStatus() { return status; }
    List<OrderLine> getLines() { return List.copyOf(lines); }
    Money getTotal() { return new Money(total, currency); }
    @Nullable String getReason() { return reason; }
    Instant getCreatedAt() { return createdAt; }
    @Nullable Address getShipping() { return shipping; }
    @Nullable Address getInvoice() { return invoice; }

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

    private boolean moveOn(OrderStatus from, OrderStatus to, @Nullable String why) {
        if (status != from) {
            return false;
        }
        status = to;
        reason = why;
        return true;
    }
}
