package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;

/**
 * A book recommended as similar to another one.
 *
 * @param similarity cosine similarity to the book the recommendation is for, from -1 to 1 (higher is more similar;
 *                   above about 0.6 usually means a shared topic)
 */
public record Recommendation(BookId bookId, double similarity) {
}
