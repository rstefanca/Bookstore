/**
 * Recommendation module. Suggests similar books for customers from embeddings of each book's title, authors, genres
 * and description, stored in PostgreSQL with pgvector. Kept up to date by listening to the catalog's book events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Recommendations")
package cz.codingmonkey.bookstore.recommendation;
