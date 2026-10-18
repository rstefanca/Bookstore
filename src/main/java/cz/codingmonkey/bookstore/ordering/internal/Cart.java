package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.AccountIdType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Type;

/**
 * A customer's cart: which books, how many of each. Prices aren't stored; they're looked up when shown and fixed
 * at checkout.
 */
@Entity
class Cart {

    static final int MAX_QUANTITY = 20;
    static final int MAX_LINES = 50;

    /** The customer's account. */
    @Id
    @Column(name = "customer_id")
    @Type(AccountIdType.class)
    private AccountId customer;

    @ElementCollection
    @CollectionTable(name = "cart_line", joinColumns = @JoinColumn(name = "customer_id"))
    private List<CartLine> lines = new ArrayList<>();

    /** Two checkouts of the same cart at once: one fails instead of both placing an order. */
    @Version
    private long version;

    protected Cart() {
    }

    Cart(AccountId customer) {
        this.customer = customer;
    }

    AccountId getCustomer() { return customer; }
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

    /**
     * Adds copies of the book, up to {@link #MAX_QUANTITY}. A book not yet in the cart is left out when the cart
     * already holds {@link #MAX_LINES} books.
     */
    void add(BookId bookId, int quantity) {
        int index = indexOf(bookId);
        if (index >= 0) {
            int total = Math.min(MAX_QUANTITY, lines.get(index).getQuantity() + quantity);
            lines.set(index, new CartLine(bookId, total));
        } else if (lines.size() < MAX_LINES) {
            lines.add(new CartLine(bookId, Math.min(MAX_QUANTITY, quantity)));
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

    public int countLines() {
        return lines.size();
    }
}
