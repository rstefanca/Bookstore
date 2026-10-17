package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.ordering.internal.OrderingResponses.CartView;
import cz.codingmonkey.bookstore.ordering.internal.OrderingResponses.OrderView;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's cart ({@code /api/cart}) and orders ({@code /api/orders}); staff list all orders.
 */
@RestController
class OrderingController {

    record Quantity(@NotNull Integer quantity) {
    }

    private final CartService carts;
    private final OrderService orders;

    OrderingController(CartService carts, OrderService orders) {
        this.carts = carts;
        this.orders = orders;
    }

    @GetMapping("/api/cart")
    CartView cart(Principal customer) {
        return carts.view(customer.getName());
    }

    /** Sets how many copies of the book are in the cart; 0 removes it. */
    @PutMapping("/api/cart/items/{bookId}")
    CartView setQuantity(@PathVariable BookId bookId, @Valid @RequestBody Quantity request, Principal customer) {
        return carts.setQuantity(customer.getName(), bookId, request.quantity());
    }

    @DeleteMapping("/api/cart/items/{bookId}")
    CartView remove(@PathVariable BookId bookId, Principal customer) {
        return carts.setQuantity(customer.getName(), bookId, 0);
    }

    @DeleteMapping("/api/cart")
    ResponseEntity<Void> clear(Principal customer) {
        carts.clear(customer.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * Places the order. Answers 202: stock and payment are handled in the background; follow the {@code Location}
     * to see the order become {@code CONFIRMED} or {@code REJECTED}.
     */
    @PostMapping("/api/cart/checkout")
    ResponseEntity<OrderView> checkout(Principal customer) {
        OrderView order = carts.checkout(customer.getName());
        return ResponseEntity.accepted().location(URI.create("/api/orders/" + order.id())).body(order);
    }

    @GetMapping("/api/orders")
    PagedModel<OrderView> ownOrders(Principal customer, Pageable page) {
        return new PagedModel<>(orders.findOwn(customer.getName(), newestFirst(page)));
    }

    @GetMapping("/api/orders/{id}")
    OrderView ownOrder(@PathVariable OrderId id, Principal customer) {
        return orders.findOwn(customer.getName(), id);
    }

    @PostMapping("/api/orders/{id}/cancel")
    OrderView cancel(@PathVariable OrderId id, Principal customer) {
        return orders.cancel(customer.getName(), id);
    }

    /** Staff: all customers' orders. */
    @GetMapping("/api/orders/all")
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
