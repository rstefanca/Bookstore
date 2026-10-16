package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer's cart: which books, how many of each. Prices aren't stored; they're looked up when shown and fixed
 * at checkout.
 */
@Entity
class Cart {

    static final int MAX_QUANTITY = 20;
    static final int MAX_LINES = 50;

    /** The customer's username. */
    @Id
    private String customer;

    @ElementCollection
    @CollectionTable(name = "cart_line", joinColumns = @JoinColumn(name = "customer"))
    private List<CartLine> lines = new ArrayList<>();

    /** Two checkouts of the same cart at once: one fails instead of both placing an order. */
    @Version
    private long version;

    protected Cart() {
    }

    Cart(String customer) {
        this.customer = customer;
    }

    String getCustomer() { return customer; }
    List<CartLine> getLines() { return List.copyOf(lines); }

    boolean isEmpty() {
        return lines.isEmpty();
    }

    /** Sets how many copies of the book are in the cart; 0 removes it. */
    void setQuantity(BookId bookId, int quantity) {
        if (quantity < 0 || quantity > MAX_QUANTITY) {
            throw new InvalidCartException(
                    "Quantity must be between 0 and %d, was %d".formatted(MAX_QUANTITY, quantity));
        }
        int index = indexOf(bookId);
        if (quantity == 0) {
            if (index >= 0) {
                lines.remove(index);
            }
        } else if (index >= 0) {
            lines.set(index, new CartLine(bookId, quantity));
        } else {
            if (lines.size() >= MAX_LINES) {
                throw new InvalidCartException("A cart holds at most %d different books".formatted(MAX_LINES));
            }
            lines.add(new CartLine(bookId, quantity));
        }
    }

    void clear() {
        lines.clear();
    }

    private int indexOf(BookId bookId) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getBookId().equals(bookId)) {
                return i;
            }
        }
        return -1;
    }
}
