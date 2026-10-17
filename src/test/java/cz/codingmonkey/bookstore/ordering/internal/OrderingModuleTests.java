package cz.codingmonkey.bookstore.ordering.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.inventory.ReservationId;
import cz.codingmonkey.bookstore.inventory.StockReleaseRequested;
import cz.codingmonkey.bookstore.inventory.StockReleased;
import cz.codingmonkey.bookstore.inventory.StockReservationFailed;
import cz.codingmonkey.bookstore.inventory.StockReservationRequested;
import cz.codingmonkey.bookstore.inventory.StockReserved;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.ordering.OrderProgressedEvent;
import cz.codingmonkey.bookstore.ordering.OrderStep;
import cz.codingmonkey.bookstore.ordering.internal.OrderingResponses.OrderView;
import cz.codingmonkey.bookstore.payment.PaymentFailed;
import cz.codingmonkey.bookstore.payment.PaymentReference;
import cz.codingmonkey.bookstore.payment.PaymentRefunded;
import cz.codingmonkey.bookstore.payment.PaymentRequested;
import cz.codingmonkey.bookstore.payment.PaymentSucceeded;
import cz.codingmonkey.bookstore.payment.RefundRequested;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.pricing.Prices;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Only the ordering module runs. The test plays the inventory and payment modules by publishing their result
 * events, and checks what the order process asks for next.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class OrderingModuleTests {

    static final BookId DUNE = new BookId(1);
    static final BookId FOUNDATION = new BookId(2);

    @MockitoBean
    BookCatalog catalog;

    @MockitoBean
    Prices prices;

    @Autowired
    CartService carts;

    @Autowired
    OrderService orders;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    EventSerializer serializer;

    String customer;

    @BeforeEach
    void catalogWithTwoBooks() {
        customer = "customer-" + UUID.randomUUID().toString().substring(0, 8);
        Map<BookId, BookDetails> books = Map.of(
                DUNE, new BookDetails(DUNE, "Dune", List.of(), List.of(), "isbn-1", 1965, null),
                FOUNDATION, new BookDetails(FOUNDATION, "Foundation", List.of(), List.of(), "isbn-2", 1951, null));
        Map<BookId, Money> bookPrices = Map.of(DUNE, Money.of("14.99", "EUR"), FOUNDATION, Money.of("11.99", "EUR"));
        given(catalog.findBook(any()))
                .willAnswer(call -> Optional.ofNullable(books.get(call.<BookId>getArgument(0))));
        given(prices.priceOf(any()))
                .willAnswer(call -> Optional.ofNullable(bookPrices.get(call.<BookId>getArgument(0))));
        given(prices.pricesOf(anyCollection())).willAnswer(call -> {
            Map<BookId, Money> found = new HashMap<>();
            for (Object id : call.<Collection<?>>getArgument(0)) {
                if (bookPrices.containsKey(id)) {
                    found.put((BookId) id, bookPrices.get(id));
                }
            }
            return found;
        });
    }

    @Test
    void checkoutSnapshotsTheCartAndAsksForStock(Scenario scenario) {
        carts.setQuantity(customer, DUNE, 2);
        carts.setQuantity(customer, FOUNDATION, 1);

        scenario.stimulate(() -> carts.checkout(customer))
                .andWaitForEventOfType(StockReservationRequested.class)
                .matching(request -> request.actor().equals(customer))
                .toArriveAndVerify((request, order) -> {
                    assertThat(request.reservationId().value()).isEqualTo(order.id().value());
                    assertThat(request.lines()).containsExactly(new StockReservationRequested.Line(DUNE, 2),
                            new StockReservationRequested.Line(FOUNDATION, 1));
                    assertThat(order.status()).isEqualTo("PENDING");
                    assertThat(order.total()).isEqualTo(Money.of("41.97", "EUR"));
                    assertThat(order.lines()).extracting(OrderingResponses.OrderLineView::title)
                            .containsExactly("Dune", "Foundation");
                });

        assertThat(carts.view(customer).items()).as("cart emptied").isEmpty();
    }

    @Test
    void happyPathReservesThenChargesThenConfirms(Scenario scenario) {
        OrderView order = placeOrder();

        scenario.publish(new StockReserved(reservation(order)))
                .andWaitForEventOfType(PaymentRequested.class)
                .matching(request -> request.reference().equals(payment(order)))
                .toArriveAndVerify(request -> {
                    assertThat(request.amount()).isEqualTo(Money.of("14.99", "EUR"));
                    assertThat(request.actor()).isEqualTo(customer);
                });
        assertThat(status(order)).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        scenario.publish(new PaymentSucceeded(payment(order)))
                .andWaitForStateChange(() -> status(order), OrderStatus.CONFIRMED::equals)
                .andVerify(status -> assertThat(status).isEqualTo(OrderStatus.CONFIRMED));
    }

    @Test
    void missingStockRejectsTheOrder(Scenario scenario) {
        OrderView order = placeOrder();

        scenario.publish(new StockReservationFailed(reservation(order), "not enough", List.of(DUNE)))
                .andWaitForStateChange(() -> orderRepository.findById(order.id()).orElseThrow(),
                        found -> found.getStatus() == OrderStatus.REJECTED)
                .andVerify(found -> assertThat(found.getReason()).isEqualTo("Not enough stock: Dune"));
    }

    @Test
    void failedPaymentRejectsTheOrderAndReleasesTheStock(Scenario scenario) {
        OrderView order = placeOrder();
        reserve(scenario, order);

        scenario.publish(new PaymentFailed(payment(order), "Declined"))
                .andWaitForEventOfType(StockReleaseRequested.class)
                .matching(release -> release.reservationId().equals(reservation(order)))
                .toArriveAndVerify(release -> assertThat(release.actor()).isEqualTo(customer));

        assertThat(orderRepository.findById(order.id()).orElseThrow())
                .satisfies(found -> {
                    assertThat(found.getStatus()).isEqualTo(OrderStatus.REJECTED);
                    assertThat(found.getReason()).isEqualTo("Payment failed: Declined");
                });
    }

    @Test
    void cancellingAConfirmedOrderReleasesStockAndRefunds(PublishedEvents events, Scenario scenario) {
        OrderView order = placeOrder();
        reserve(scenario, order);
        scenario.publish(new PaymentSucceeded(payment(order)))
                .andWaitForStateChange(() -> status(order), OrderStatus.CONFIRMED::equals)
                .andVerify(status -> { });

        assertThat(orders.cancel(customer, order.id()).status()).isEqualTo("CANCELLED");

        assertThat(events.ofType(StockReleaseRequested.class)
                .matching(release -> release.reservationId().equals(reservation(order)))).hasSize(1);
        assertThat(events.ofType(RefundRequested.class)
                .matching(refund -> refund.reference().equals(payment(order)))).hasSize(1);
    }

    @Test
    void onlyConfirmedOrdersCanBeCancelled() {
        OrderView order = placeOrder();

        assertThatThrownBy(() -> orders.cancel(customer, order.id()))
                .isInstanceOf(OrderNotCancellableException.class)
                .hasMessage("Order %s is PENDING; only confirmed orders can be cancelled".formatted(order.id()));
    }

    @Test
    void repeatedAndLateResultsAreIgnored(PublishedEvents events, Scenario scenario) {
        OrderView order = placeOrder();
        reserve(scenario, order);

        // A repeated "stock reserved" (e.g. a retry) and a late failure arrive after the order moved on.
        scenario.publish(new StockReserved(reservation(order))).andWaitForStateChange(() -> true).andVerify(x -> { });
        scenario.publish(new StockReservationFailed(reservation(order), "late", List.of(DUNE)))
                .andWaitForStateChange(() -> true).andVerify(x -> { });

        // For a while, there's still exactly one payment request and the order still awaits payment.
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(events.ofType(PaymentRequested.class).matching(r -> r.reference().equals(payment(order))))
                    .hasSize(1);
            assertThat(steps(events, order)).containsOnlyOnce(OrderStep.STOCK_RESERVED);
            assertThat(status(order)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        });
    }

    @Test
    void cantCheckOutBooksNoLongerForSale() {
        carts.setQuantity(customer, DUNE, 1);
        given(prices.pricesOf(anyCollection())).willReturn(Map.of());

        assertThatThrownBy(() -> carts.checkout(customer))
                .isInstanceOf(NotForSaleException.class)
                .hasMessageStartingWith("No longer for sale: Dune");
        assertThat(carts.view(customer).items()).as("cart unchanged").hasSize(1);
    }

    @Test
    void cantCheckOutAnEmptyCart() {
        assertThatThrownBy(() -> carts.checkout(customer)).isInstanceOf(EmptyCartException.class);
    }

    @Test
    void cantAddBooksThatArentForSale() {
        given(prices.priceOf(DUNE)).willReturn(Optional.empty());

        assertThatThrownBy(() -> carts.setQuantity(customer, DUNE, 1)).isInstanceOf(NotForSaleException.class);
        assertThatThrownBy(() -> carts.setQuantity(customer, FOUNDATION, 21))
                .isInstanceOf(InvalidCartException.class);
    }

    @Test
    void customersSeeOnlyTheirOwnOrders() {
        OrderView order = placeOrder();

        assertThat(orders.findOwn(customer, order.id()).id()).isEqualTo(order.id());
        assertThatThrownBy(() -> orders.findOwn("someone-else", order.id()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void checkoutPublishesPlaced(PublishedEvents events) {
        carts.setQuantity(customer, DUNE, 2);
        carts.setQuantity(customer, FOUNDATION, 1);

        OrderView order = carts.checkout(customer);

        assertThat(progress(events, order)).singleElement().satisfies(placed -> {
            assertThat(placed.step()).isEqualTo(OrderStep.PLACED);
            assertThat(placed.detail()).isEqualTo("3 copies, 41.97 EUR");
            assertThat(placed.actor()).isEqualTo(customer);
        });
    }

    @Test
    void happyPathPublishesEachStep(PublishedEvents events, Scenario scenario) {
        OrderView order = placeOrder();
        reserve(scenario, order);

        awaitStep(scenario, new PaymentSucceeded(payment(order)), order, OrderStep.CONFIRMED);

        assertThat(steps(events, order)).containsExactly(
                OrderStep.PLACED, OrderStep.STOCK_RESERVED, OrderStep.PAYMENT_CHARGED, OrderStep.CONFIRMED);
        assertThat(detail(events, order, OrderStep.PAYMENT_CHARGED)).isEqualTo("Charged 14.99 EUR");
        assertThat(progress(events, order)).allSatisfy(step -> assertThat(step.actor()).isEqualTo(customer));
        // Steps published together (charged, then confirmed) still get distinct times in the right order, so
        // listeners that handle them in parallel (like audit) can sort them.
        assertThat(progress(events, order)).extracting(OrderProgressedEvent::occurredAt)
                .doesNotHaveDuplicates().isSorted();
    }

    @Test
    void missingStockPublishesMissingThenRejected(PublishedEvents events, Scenario scenario) {
        OrderView order = placeOrder();

        awaitStep(scenario, new StockReservationFailed(reservation(order), "x", List.of(DUNE)), order,
                OrderStep.REJECTED);

        assertThat(steps(events, order))
                .containsExactly(OrderStep.PLACED, OrderStep.STOCK_MISSING, OrderStep.REJECTED);
        assertThat(detail(events, order, OrderStep.STOCK_MISSING)).isEqualTo("Not enough stock: Dune");
        assertThat(detail(events, order, OrderStep.REJECTED)).isEqualTo("Not enough stock: Dune");
    }

    @Test
    void declinedPaymentPublishesDeclinedThenRejected(PublishedEvents events, Scenario scenario) {
        OrderView order = placeOrder();
        reserve(scenario, order);

        awaitStep(scenario, new PaymentFailed(payment(order), "Declined"), order, OrderStep.REJECTED);

        assertThat(steps(events, order)).containsExactly(OrderStep.PLACED, OrderStep.STOCK_RESERVED,
                OrderStep.PAYMENT_DECLINED, OrderStep.REJECTED);
        assertThat(detail(events, order, OrderStep.PAYMENT_DECLINED)).isEqualTo("Declined");
    }

    @Test
    void cancelAndUndoArePublished(PublishedEvents events, Scenario scenario) {
        OrderView order = placeOrder();
        reserve(scenario, order);
        awaitStep(scenario, new PaymentSucceeded(payment(order)), order, OrderStep.CONFIRMED);

        orders.cancel(customer, order.id());
        awaitStep(scenario, new StockReleased(reservation(order)), order, OrderStep.STOCK_RELEASED);
        awaitStep(scenario, new PaymentRefunded(payment(order)), order, OrderStep.PAYMENT_REFUNDED);

        assertThat(steps(events, order)).endsWith(
                OrderStep.CANCELLED, OrderStep.STOCK_RELEASED, OrderStep.PAYMENT_REFUNDED);
        assertThat(detail(events, order, OrderStep.CANCELLED)).isEqualTo("Cancelled by the customer");
        assertThat(detail(events, order, OrderStep.PAYMENT_REFUNDED)).isEqualTo("Refunded 14.99 EUR");
    }

    @Test
    void repeatedUndoResultsArePublishedOnce(PublishedEvents events, Scenario scenario) {
        OrderView order = placeOrder();
        reserve(scenario, order);
        awaitStep(scenario, new PaymentSucceeded(payment(order)), order, OrderStep.CONFIRMED);
        orders.cancel(customer, order.id());
        awaitStep(scenario, new StockReleased(reservation(order)), order, OrderStep.STOCK_RELEASED);
        awaitStep(scenario, new PaymentRefunded(payment(order)), order, OrderStep.PAYMENT_REFUNDED);

        // Both results delivered again (e.g. resubmitted after a crash).
        scenario.publish(new StockReleased(reservation(order))).andWaitForStateChange(() -> true).andVerify(x -> { });
        scenario.publish(new PaymentRefunded(payment(order))).andWaitForStateChange(() -> true).andVerify(x -> { });

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(
                steps(events, order)).containsOnlyOnce(OrderStep.STOCK_RELEASED, OrderStep.PAYMENT_REFUNDED));
    }

    @Test
    void progressEventsSurviveTheEventRegistry() {
        var event = new OrderProgressedEvent(new OrderId(5), OrderStep.PAYMENT_DECLINED, "Declined", "rita",
                Instant.parse("2026-10-16T08:00:00.123456Z"));

        assertThat(serializer.deserialize(serializer.serialize(event), OrderProgressedEvent.class)).isEqualTo(event);
    }

    private void awaitStep(Scenario scenario, Object result, OrderView order, OrderStep step) {
        scenario.publish(result)
                .andWaitForEventOfType(OrderProgressedEvent.class)
                .matching(progress -> progress.orderId().equals(order.id()) && progress.step() == step)
                .toArrive();
    }

    private static List<OrderProgressedEvent> progress(PublishedEvents events, OrderView order) {
        return StreamSupport.stream(events.ofType(OrderProgressedEvent.class)
                .matching(progress -> progress.orderId().equals(order.id())).spliterator(), false).toList();
    }

    private static List<OrderStep> steps(PublishedEvents events, OrderView order) {
        return progress(events, order).stream().map(OrderProgressedEvent::step).toList();
    }

    private static String detail(PublishedEvents events, OrderView order, OrderStep step) {
        return progress(events, order).stream().filter(p -> p.step() == step).findFirst().orElseThrow().detail();
    }

    private OrderView placeOrder() {
        carts.setQuantity(customer, DUNE, 1);
        return carts.checkout(customer);
    }

    private void reserve(Scenario scenario, OrderView order) {
        scenario.publish(new StockReserved(reservation(order)))
                .andWaitForEventOfType(PaymentRequested.class)
                .matching(request -> request.reference().equals(payment(order)))
                .toArrive();
    }

    private OrderStatus status(OrderView order) {
        return orderRepository.findById(order.id()).orElseThrow().getStatus();
    }

    private static ReservationId reservation(OrderView order) {
        return new ReservationId(order.id().value());
    }

    private static PaymentReference payment(OrderView order) {
        return new PaymentReference(order.id().value());
    }
}
