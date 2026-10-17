package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.List;

/**
 * A book picked for the customer.
 *
 * @param authors names, in credited order
 */
record PickedBook(BookId bookId, String title, List<String> authors) {
}
