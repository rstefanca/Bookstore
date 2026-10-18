package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookIdType;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.AccountIdType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Type;

/**
 * A customer's rating of a book, read-only, for the criteria subqueries {@link RatingRanking} adds to the catalog's
 * book search. Ratings are written by {@link BookRatings}; only the columns those subqueries need are mapped.
 */
@Entity
@Immutable
@Table(name = "book_rating")
class RatedBook implements Serializable {

    @Id
    @Column(name = "book_id")
    @Type(BookIdType.class)
    private BookId bookId;

    @Id
    @Column(name = "customer_id")
    @Type(AccountIdType.class)
    private AccountId customerId;

    /** The column is {@code SMALLINT}. */
    private short stars;

    protected RatedBook() {
    }

    // Hibernate uses the entity itself as the id of a composite key without an id class.
    @Override
    public boolean equals(Object other) {
        return other instanceof RatedBook that && Objects.equals(bookId, that.bookId)
                && Objects.equals(customerId, that.customerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bookId, customerId);
    }
}
