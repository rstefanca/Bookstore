package cz.codingmonkey.bookstore.bookcatalog;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;

/**
 * Extension point for other modules to sort a {@link BookSearch} by their own data, typically with a correlated
 * scalar subquery. The catalog adds its own tie-breakers (title, then id) after it.
 */
@FunctionalInterface
public interface BookOrder {

    /**
     * @param bookId the id of the book being sorted, to correlate a subquery with
     * @param query  the catalog's query, to create subqueries from
     */
    Order toOrder(Expression<BookId> bookId, AbstractQuery<?> query, CriteriaBuilder cb);
}
