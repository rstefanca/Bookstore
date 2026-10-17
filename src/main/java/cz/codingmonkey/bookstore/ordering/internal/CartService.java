package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.inventory.ReservationId;
import cz.codingmonkey.bookstore.inventory.StockReservationRequested;
import cz.codingmonkey.bookstore.ordering.OrderProgressedEvent;
import cz.codingmonkey.bookstore.ordering.OrderStep;
import cz.codingmonkey.bookstore.ordering.internal.OrderingResponses.CartItem;
import cz.codingmonkey.bookstore.ordering.internal.OrderingResponses.CartView;
import cz.codingmonkey.bookstore.ordering.internal.OrderingResponses.OrderView;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.pricing.Prices;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CartService {

    private static final ChangeLog changes = ChangeLog.of(CartService.class);

    private final CartRepository carts;
    private final OrderRepository orders;
    private final BookCatalog catalog;
    private final Prices prices;
    private final ApplicationEventPublisher events;

    CartService(CartRepository carts, OrderRepository orders, BookCatalog catalog, Prices prices,
            ApplicationEventPublisher events) {
        this.carts = carts;
        this.orders = orders;
        this.catalog = catalog;
        this.prices = prices;
        this.events = events;
    }

    /** The cart with current titles and prices. */
    CartView view(String customer) {
        List<CartLine> lines = carts.findById(customer).map(Cart::getLines).orElse(List.of());
        Map<BookId, Money> current = prices.pricesOf(lines.stream().map(CartLine::getBookId).toList());
        List<CartItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Currency currency = null;
        for (CartLine line : lines) {
            String title = catalog.findBook(line.getBookId()).map(BookDetails::title).orElse(null);
            Money price = current.get(line.getBookId());
            Money lineTotal = price == null ? null : times(price, line.getQuantity());
            if (lineTotal != null) {
                total = total.add(lineTotal.amount());
                currency = lineTotal.currency();
            }
            items.add(new CartItem(line.getBookId(), title, line.getQuantity(), price, lineTotal));
        }
        return new CartView(items, currency == null ? null : new Money(total, currency));
    }

    /** Puts the book in the cart (or changes how many); 0 removes it. Only books for sale can be added. */
    @Transactional
    CartView setQuantity(String customer, BookId bookId, int quantity) {
        if (quantity > 0 && (catalog.findBook(bookId).isEmpty() || prices.priceOf(bookId).isEmpty())) {
            throw new NotForSaleException("Book " + bookId + " doesn't exist or isn't for sale");
        }
        Cart cart = carts.findById(customer).orElseGet(() -> carts.save(new Cart(customer)));
        cart.setQuantity(bookId, quantity);
        return view(customer);
    }

    @Transactional
    void clear(String customer) {
        carts.findById(customer).ifPresent(Cart::clear);
    }

    /**
     * Turns the cart into an order with today's titles and prices, empties the cart, and starts the order process
     * by asking the inventory to reserve the stock.
     */
    @Transactional
    OrderView checkout(String customer) {
        Cart cart = carts.findById(customer).filter(c -> !c.isEmpty()).orElseThrow(EmptyCartException::new);
        Map<BookId, Money> current = prices.pricesOf(cart.getLines().stream().map(CartLine::getBookId).toList());

        List<OrderLine> lines = new ArrayList<>();
        List<String> notForSale = new ArrayList<>();
        Currency currency = null;
        for (CartLine line : cart.getLines()) {
            Optional<BookDetails> book = catalog.findBook(line.getBookId());
            Money price = current.get(line.getBookId());
            if (book.isEmpty() || price == null) {
                notForSale.add(book.map(BookDetails::title).orElse("book " + line.getBookId()));
                continue;
            }
            currency = price.currency();
            lines.add(new OrderLine(line.getBookId(), book.get().title(), line.getQuantity(), price.amount()));
        }
        if (!notForSale.isEmpty()) {
            throw new NotForSaleException("No longer for sale: " + String.join(", ", notForSale)
                    + ". Remove them from the cart and check out again.");
        }

        CustomerOrder order = orders.save(new CustomerOrder(customer, lines, currency));
        cart.clear();

        report(customer, order, lines);

        return OrderView.from(order);
    }

    private void report(String customer, CustomerOrder order, List<OrderLine> lines) {
        events.publishEvent(new StockReservationRequested(new ReservationId(order.getId().value()),
                lines.stream().map(line -> new StockReservationRequested.Line(line.getBookId(), line.getQuantity()))
                        .toList(),
                customer));

        int copies = lines.stream().mapToInt(OrderLine::getQuantity).sum();
        events.publishEvent(new OrderProgressedEvent(order.getId(), OrderStep.PLACED,
                "%d copies, %s".formatted(copies, order.getTotal()), customer, StepClock.next()));

        changes.info("Placed order {} ({} lines, total {})", order.getId(), lines.size(), order.getTotal());
    }

    private static Money times(Money price, int quantity) {
        return new Money(price.amount().multiply(BigDecimal.valueOf(quantity)), price.currency());
    }
}
