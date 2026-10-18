package cz.codingmonkey.bookstore.wishlist.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores the wishlists in {@code wishlist_item}, one row per account and book.
 */
@Repository
class WishlistItems {

    private final JdbcClient jdbc;

    WishlistItems(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean exists(AccountId account, BookId book) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM wishlist_item WHERE account_id = ? AND book_id = ?)")
                .param(account.value())
                .param(book.value())
                .query(Boolean.class)
                .single();
    }

    int count(AccountId account) {
        return jdbc.sql("SELECT count(*) FROM wishlist_item WHERE account_id = ?")
                .param(account.value())
                .query(Integer.class)
                .single();
    }

    /** Adds the book unless it is already on the list. */
    void insert(AccountId account, BookId book, Instant addedAt) {
        jdbc.sql("""
                INSERT INTO wishlist_item (account_id, book_id, added_at) VALUES (?, ?, ?)
                ON CONFLICT (account_id, book_id) DO NOTHING
                """)
                .param(account.value())
                .param(book.value())
                .param(OffsetDateTime.ofInstant(addedAt, ZoneOffset.UTC))
                .update();
    }

    /** Returns how many entries were removed: 0 or 1. */
    int delete(AccountId account, BookId book) {
        return jdbc.sql("DELETE FROM wishlist_item WHERE account_id = ? AND book_id = ?")
                .param(account.value())
                .param(book.value())
                .update();
    }

    /** Removes the book from every list; returns how many entries went. */
    int deleteAll(BookId book) {
        return jdbc.sql("DELETE FROM wishlist_item WHERE book_id = ?").param(book.value()).update();
    }

    /** Empties the account's list; returns how many entries went. */
    int deleteAll(AccountId account) {
        return jdbc.sql("DELETE FROM wishlist_item WHERE account_id = ?").param(account.value()).update();
    }
}
