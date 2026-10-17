-- Owned by the ordering module: finds a book's orders when the book is about to be deleted.
CREATE INDEX order_line_book_idx ON order_line (book_id);
