package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookIdType;
import cz.codingmonkey.bookstore.pricing.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Type;
import org.jspecify.annotations.Nullable;

/**
 * A period during which a book had a given price. Open ({@code validUntil == null}) while it is the current price.
 */
@Entity
@Table(name = "book_price_period")
class PricePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Type(PricePeriodIdType.class)
    private PricePeriodId id;

    @Column(nullable = false)
    @Type(BookIdType.class)
    private BookId bookId;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false, precision = 12, scale = 2))
    @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    private Money price;

    @Column(nullable = false)
    private Instant validFrom;

    private @Nullable Instant validUntil;

    protected PricePeriod() {
    }

    PricePeriod(BookId bookId, Money price, Instant validFrom) {
        this.bookId = bookId;
        this.price = price;
        this.validFrom = validFrom;
    }

    Money getPrice() { return price; }
    Instant getValidFrom() { return validFrom; }
    @Nullable Instant getValidUntil() { return validUntil; }

    void close(Instant at) {
        this.validUntil = at;
    }
}
