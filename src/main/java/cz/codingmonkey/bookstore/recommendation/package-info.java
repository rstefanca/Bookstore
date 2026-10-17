/**
 * Recommendation module. Finds similar books from embeddings of each book's title, authors, genres and description,
 * stored in PostgreSQL with pgvector, and offers them by id through {@link Recommendations}; the storefront shows them
 * to customers. Kept up to date by listening to the catalog's book events.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Recommendations")
package cz.codingmonkey.bookstore.recommendation;
