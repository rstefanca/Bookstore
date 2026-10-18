package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.customer.Address;
import cz.codingmonkey.bookstore.customer.CustomerAddresses;
import cz.codingmonkey.bookstore.inventory.Stock;
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
    private final Stock stock;
    private final CustomerAddresses addresses;
    private final ApplicationEventPublisher events;

    CartService(CartRepository carts,
                OrderRepository orders,
                BookCatalog catalog,
                Prices prices,
                Stock stock,
                CustomerAddresses addresses,
                ApplicationEventPublisher events) {
        this.carts = carts;
        this.orders = orders;
        this.catalog = catalog;
        this.prices = prices;
        this.stock = stock;
        this.addresses = addresses;
        this.events = events;
    }

    /** The cart with current titles and prices. */
    CartView view(String customer) {
        List<CartLine> lines = carts.findById(customer).map(Cart::getLines).orElse(List.of());
        Map<BookId, Money> currentBookPrices = prices.pricesOf(lines.stream().map(CartLine::getBookId).toList());

        List<CartItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Currency currency = null;
        for (CartLine line : lines) {

            Optional<BookDetails> book = catalog.findBook(line.getBookId());
            String title = book.map(BookDetails::title).orElse(null);

            Money price = currentBookPrices.get(line.getBookId());
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
     * by asking the inventory to reserve the stock. Its books can't be deleted until the order is committed (and
     * then {@link SoldBookDeletionPolicy} keeps them). The order keeps a copy of the shipping and invoice address:
     * the one in the request, else the customer's saved one; if either is missing nothing is ordered.
     */
    @Transactional
    OrderView checkout(String customer, CheckoutRequest request) {
        Cart cart = carts.findById(customer).filter(c -> !c.isEmpty()).orElseThrow(EmptyCartException::new);

        Address shippingAddress = request.shipping() != null ? request.shipping()
                : addresses.shipping(customer).orElse(null);
        Address invoiceAddress = request.invoice() != null ? request.invoice()
                : addresses.invoice(customer).orElse(null);
        if (shippingAddress == null || invoiceAddress == null) {
            throw new MissingAddressException(shippingAddress == null, invoiceAddress == null);
        }

        int cartSize = cart.countLines();
        List<CartLine> cartLines = cart.getLines();
        Map<BookId, Money> currentPrices = getCurrentPrices(cartLines);

        List<OrderLine> lines = new ArrayList<>(cartSize);
        List<String> notForSale = new ArrayList<>(cartSize);
        Currency currency = null;
        for (CartLine line : cartLines) {
            Optional<BookDetails> book = getBookDetails(line);

            Money price = currentPrices.get(line.getBookId());
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
        
        checkStock(lines);

        CustomerOrder order = orders.save(new CustomerOrder(customer, lines, currency, shippingAddress, invoiceAddress));
        cart.clear();

        reserveStockAndPlaceOrder(customer, order, lines);

        return OrderView.from(order);
    }

    private Optional<BookDetails> getBookDetails(CartLine line) {
        // Locked until the order is committed, so the book can't be deleted while it is being ordered.
        return catalog.existsAndLockAgainstDeletion(line.getBookId())
                ? catalog.findBook(line.getBookId())
                : Optional.empty();
    }

    private Map<BookId, Money> getCurrentPrices(List<CartLine> cartLines) {
        List<BookId> bookIds = cartLines.stream().map(CartLine::getBookId).toList();
        return prices.pricesOf(bookIds);
    }

    /**
     * Puts a rejected order's lines back into the customer's cart, added to whatever is there now (see
     * {@link Cart#add}). Books no longer for sale go back too; checkout refuses them as for any cart.
     */
    @Transactional
    void restore(CustomerOrder order) {
        Cart cart = carts.findAndLockByCustomer(order.getCustomer())
                .orElseGet(() -> {
                    Cart newCart = new Cart(order.getCustomer());
                    return carts.save(newCart);
                });
        order.getLines().forEach(line -> cart.add(line.getBookId(), line.getQuantity()));
        changes.info("Order {}: put {} lines back into the cart", order.getId(), order.getLines().size());
    }

    /**
     * Refuses the checkout, keeping the cart, if any book has fewer copies in stock than ordered. Reads without
     * reserving: if the stock goes in the meantime, the inventory rejects the order and the cart is refilled
     * the cart.
     */
    private void checkStock(List<OrderLine> lines) {
        Map<BookId, Integer> available = stock.availableOf(lines.stream().map(OrderLine::getBookId).toList());
        List<String> shortBooks = lines.stream()
                .filter(line -> available.getOrDefault(line.getBookId(), 0) < line.getQuantity())
                .map(line -> "%s (%d left)".formatted(line.getTitle(), available.getOrDefault(line.getBookId(), 0)))
                .toList();
        if (!shortBooks.isEmpty()) {
            throw new OutOfStockException("Not enough stock: " + String.join(", ", shortBooks)
                    + ". Change the quantities and check out again.");
        }
    }

    private void reserveStockAndPlaceOrder(String customer, CustomerOrder order, List<OrderLine> lines) {
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
