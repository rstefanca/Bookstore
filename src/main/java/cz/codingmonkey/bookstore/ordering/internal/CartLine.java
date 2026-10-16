package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookIdType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.Type;

@Embeddable
class CartLine {

    @Column(name = "book_id", nullable = false)
    @Type(BookIdType.class)
    private BookId bookId;

    @Column(nullable = false)
    private int quantity;

    protected CartLine() {
    }

    CartLine(BookId bookId, int quantity) {
        this.bookId = bookId;
        this.quantity = quantity;
    }

    BookId getBookId() { return bookId; }
    int getQuantity() { return quantity; }
}
