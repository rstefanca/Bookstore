-- Owned by the wishlist module. Books and accounts belong to other modules, so they are referenced by id only.
CREATE TABLE wishlist_item (
    account_id BIGINT                   NOT NULL,
    book_id    BIGINT                   NOT NULL,
    added_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (account_id, book_id)
);
CREATE INDEX wishlist_item_book_idx ON wishlist_item (book_id);
