/**
 * Book catalog module: books, their authors and genres. Exposes {@link cz.codingmonkey.bookstore.bookcatalog.BookId},
 * the {@link cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent} and
 * {@link cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent} events, and the
 * {@link cz.codingmonkey.bookstore.bookcatalog.BookCatalog} and
 * {@link cz.codingmonkey.bookstore.bookcatalog.BookDeletionPolicy} interfaces; everything in {@code internal} is
 * private to the module.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Book Catalog")
package cz.codingmonkey.bookstore.bookcatalog;
