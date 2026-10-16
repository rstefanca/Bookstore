package cz.codingmonkey.bookstore.ordering.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.pricing.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The order's state machine, without Spring or a database.
 */
class CustomerOrderTest {

    final CustomerOrder order = new CustomerOrder("rita", List.of(
            new OrderLine(new BookId(1), "Dune", 2, new BigDecimal("14.99")),
            new OrderLine(new BookId(2), "Foundation", 1, new BigDecimal("11.99"))), Currency.getInstance("EUR"));

    @Test
    void startsPendingWithTheTotalOfAllLines() {
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotal()).isEqualTo(Money.of("41.97", "EUR"));
    }

    @Test
    void movesOnOnlyFromTheExpectedState() {
        assertThat(order.paid()).as("can't be paid before stock is reserved").isFalse();
        assertThat(order.stockReserved()).isTrue();
        assertThat(order.stockReserved()).as("repeated").isFalse();
        assertThat(order.stockUnavailable("late")).as("late").isFalse();
        assertThat(order.paid()).isTrue();
        assertThat(order.paymentFailed("late")).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getReason()).isNull();
    }

    @Test
    void rejectionsKeepTheReason() {
        assertThat(order.stockUnavailable("Not enough stock: Dune")).isTrue();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getReason()).isEqualTo("Not enough stock: Dune");
        assertThat(order.stockReserved()).isFalse();
    }

    @Test
    void onlyConfirmedOrdersCanBeCancelled() {
        assertThatThrownBy(order::cancel).isInstanceOf(OrderNotCancellableException.class);
        order.stockReserved();
        assertThatThrownBy(order::cancel).isInstanceOf(OrderNotCancellableException.class);
        order.paid();

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(order::cancel).as("twice").isInstanceOf(OrderNotCancellableException.class);
    }
}
