/**
 * Rating module. Holds customers' star ratings (1 to 5) of books, one per customer and book, and summarizes them
 * for other modules through {@link cz.codingmonkey.bookstore.rating.Ratings}. Ratings are removed with their book.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Ratings")
package cz.codingmonkey.bookstore.rating;
