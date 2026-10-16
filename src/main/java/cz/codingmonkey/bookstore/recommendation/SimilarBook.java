package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.List;

/**
 * @param similarity cosine similarity to the book the recommendation is for, from -1 to 1 (higher is more similar)
 */
record SimilarBook(BookId bookId, String title, List<String> authors, double similarity) {
}
