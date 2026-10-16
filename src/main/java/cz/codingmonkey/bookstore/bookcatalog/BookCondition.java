package cz.codingmonkey.bookstore.bookcatalog;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * Extension point for other modules to filter a {@link BookSearch} by their own data. The catalog applies it inside
 * its own query, typically as a correlated {@code EXISTS} subquery against the other module's entity, so matching
 * book ids never leave the database and the catalog never needs to know about the other module.
 */
@FunctionalInterface
public interface BookCondition {

    /**
     * @param bookId the id of the book being matched, to correlate a subquery with
     * @param query  the catalog's query, to create subqueries from
     */
    Predicate toPredicate(Expression<BookId> bookId, AbstractQuery<?> query, CriteriaBuilder cb);
}
