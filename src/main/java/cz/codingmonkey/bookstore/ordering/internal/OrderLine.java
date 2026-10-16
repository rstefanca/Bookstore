package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookIdType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import org.hibernate.annotations.Type;

/** A book as ordered: title and unit price as they were at checkout. */
@Embeddable
class OrderLine {

    @Column(name = "book_id", nullable = false)
    @Type(BookIdType.class)
    private BookId bookId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    protected OrderLine() {
    }

    OrderLine(BookId bookId, String title, int quantity, BigDecimal unitPrice) {
        this.bookId = bookId;
        this.title = title;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    BookId getBookId() { return bookId; }
    String getTitle() { return title; }
    int getQuantity() { return quantity; }
    BigDecimal getUnitPrice() { return unitPrice; }

    BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
