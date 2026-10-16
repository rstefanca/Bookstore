package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookIdType;
import cz.codingmonkey.bookstore.pricing.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.hibernate.annotations.Type;

/**
 * The current price of a book that is for sale.
 */
@Entity
class BookPrice {

    @Id
    @Type(BookIdType.class)
    private BookId bookId;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false, precision = 12, scale = 2))
    @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    private Money price;

    @Version
    private Long version;

    protected BookPrice() {
    }

    BookPrice(BookId bookId, Money price) {
        this.bookId = bookId;
        this.price = price;
    }

    BookId getBookId() { return bookId; }
    Money getPrice() { return price; }

    void changeTo(Money price) {
        this.price = price;
    }
}
