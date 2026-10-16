/**
 * Inventory module. Tracks stock levels per book: a book has a stock record from its first restock on. Prevents
 * deleting books that are still in stock via {@link cz.codingmonkey.bookstore.bookcatalog.BookDeletionPolicy}.
 * Every stock change publishes {@link cz.codingmonkey.bookstore.inventory.StockAdjustedEvent}.
 * <p>
 * Other modules reserve stock with {@link cz.codingmonkey.bookstore.inventory.StockReservationRequested} (answered
 * with {@link cz.codingmonkey.bookstore.inventory.StockReserved} or
 * {@link cz.codingmonkey.bookstore.inventory.StockReservationFailed}) and give it back with
 * {@link cz.codingmonkey.bookstore.inventory.StockReleaseRequested} (announced with
 * {@link cz.codingmonkey.bookstore.inventory.StockReleased}). The inventory doesn't know who reserves.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Inventory")
package cz.codingmonkey.bookstore.inventory;
