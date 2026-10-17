package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.ordering.internal.OrderingResponses.CartView;
import cz.codingmonkey.bookstore.ordering.internal.OrderingResponses.OrderView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.security.Principal;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's cart ({@code /api/cart}) and orders ({@code /api/orders}); staff list all orders.
 */
@RestController
class OrderingController {

    /**
     * How many copies of a book to have in the cart.
     *
     * @param quantity 0 to 20; 0 removes the book
     */
    record Quantity(@Schema(example = "2") @NotNull Integer quantity) {
    }

    private final CartService carts;
    private final OrderService orders;

    OrderingController(CartService carts, OrderService orders) {
        this.carts = carts;
        this.orders = orders;
    }

    /** Returns your cart with current prices and the total. */
    @GetMapping("/api/cart")
    @Tag(name = "Cart", description = """
            The signed-in customer's cart (`CUSTOMER`). It shows current prices; checkout turns it into an order with \
            those prices fixed.""")
    CartView cart(Principal customer) {
        return carts.view(customer.getName());
    }

    /** Sets how many copies of a book are in your cart; 0 removes it. Only books for sale can be added. */
    @PutMapping("/api/cart/items/{bookId}")
    @Tag(name = "Cart")
    @ApiResponse(responseCode = "200", description = "The updated cart")
    @ApiResponse(responseCode = "400", description = """
            quantity isn't between 0 and 20, or the cart already holds 50 different books""")
    @ApiResponse(responseCode = "409", description = "The book doesn't exist or isn't for sale")
    CartView setQuantity(@PathVariable BookId bookId, @Valid @RequestBody Quantity request, Principal customer) {
        return carts.setQuantity(customer.getName(), bookId, request.quantity());
    }

    /** Removes a book from your cart. */
    @DeleteMapping("/api/cart/items/{bookId}")
    @Tag(name = "Cart")
    CartView remove(@PathVariable BookId bookId, Principal customer) {
        return carts.setQuantity(customer.getName(), bookId, 0);
    }

    /** Empties your cart. */
    @DeleteMapping("/api/cart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Tag(name = "Cart")
    void clear(Principal customer) {
        carts.clear(customer.getName());
    }

    /**
     * Orders everything in your cart.
     * <p>
     * Titles and prices are fixed in the order and the cart is emptied. The answer is 202 with the {@code PENDING}
     * order: stock is reserved and the payment charged in the background. Follow the {@code Location} to see the
     * order become {@code CONFIRMED} or {@code REJECTED}.
     */
    @PostMapping("/api/cart/checkout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Tag(name = "Cart")
    @ApiResponse(responseCode = "202", description = "The order, still PENDING")
    @ApiResponse(responseCode = "409", description = """
            The cart is empty, some books are no longer for sale, or the cart was changed by another request at the \
            same time""")
    ResponseEntity<OrderView> checkout(Principal customer) {
        OrderView order = carts.checkout(customer.getName());
        return ResponseEntity.accepted().location(URI.create("/api/orders/" + order.id())).body(order);
    }

    /** Lists your orders, newest first. */
    @GetMapping("/api/orders")
    @Tag(name = "Orders", description = """
            Orders placed from the cart: customers see their own (`CUSTOMER`), staff see all. A new order is \
            `PENDING`; once stock is reserved it is `AWAITING_PAYMENT`, then `CONFIRMED`, or `REJECTED` when stock \
            or payment fails (`reason` says why). A confirmed order can be `CANCELLED`.""")
    PagedModel<OrderView> ownOrders(Principal customer, Pageable page) {
        return new PagedModel<>(orders.findOwn(customer.getName(), newestFirst(page)));
    }

    /** Returns one of your orders, e.g. to see whether it has been confirmed yet. */
    @GetMapping("/api/orders/{id}")
    @Tag(name = "Orders")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "404", description = "You have no order with this id")
    OrderView ownOrder(@PathVariable OrderId id, Principal customer) {
        return orders.findOwn(customer.getName(), id);
    }

    /**
     * Cancels one of your confirmed orders.
     * <p>
     * The order is {@code CANCELLED} right away; the stock is put back and the payment refunded in the background.
     */
    @PostMapping("/api/orders/{id}/cancel")
    @Tag(name = "Orders")
    @ApiResponse(responseCode = "200", description = "The cancelled order")
    @ApiResponse(responseCode = "404", description = "You have no order with this id")
    @ApiResponse(responseCode = "409", description = "The order isn't CONFIRMED")
    OrderView cancel(@PathVariable OrderId id, Principal customer) {
        return orders.cancel(customer.getName(), id);
    }

    /** Lists all customers' orders, newest first; staff only. */
    @GetMapping("/api/orders/all")
    @Tag(name = "Orders")
    PagedModel<OrderView> allOrders(Pageable page) {
        return new PagedModel<>(orders.findAll(newestFirst(page)));
    }

    /** The same cart was checked out or changed twice at once; the client should reload it and retry. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentChange() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The cart or order was changed at the same time by another request; reload it and retry");
    }

    private static Pageable newestFirst(Pageable page) {
        return PageRequest.of(page.getPageNumber(), page.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
