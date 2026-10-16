package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookIdType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.hibernate.annotations.Type;

@Entity
public class InventoryItem {

    @Id
    @Type(BookIdType.class)
    private BookId bookId;

    @Column(nullable = false)
    private int quantity;

    @Version
    private Long version;

    protected InventoryItem() {
    }

    InventoryItem(BookId bookId) {
        this.bookId = bookId;
        this.quantity = 0;
    }

    public BookId getBookId() { return bookId; }
    public int getQuantity() { return quantity; }

    boolean isInStock() {
        return quantity > 0;
    }

    void restock(int amount) {
        quantity += amount;
    }

    void withdraw(int amount) {
        if (amount > quantity) {
            throw new InsufficientStockException(bookId, amount, quantity);
        }
        quantity -= amount;
    }
}
