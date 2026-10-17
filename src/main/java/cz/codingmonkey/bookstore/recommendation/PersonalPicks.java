package cz.codingmonkey.bookstore.recommendation;

import java.util.List;

/**
 * Books picked for the signed-in customer, best first; fewer than requested when there aren't enough candidates.
 *
 * @param personal true when picked by similarity to the books the customer liked (rated 4 or 5 stars); false when
 *                 the list is the store's top-rated books, for customers who haven't liked a book yet
 * @param books    books for sale that the customer hasn't rated
 */
record PersonalPicks(boolean personal, List<PickedBook> books) {
}
