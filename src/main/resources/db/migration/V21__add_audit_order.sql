-- Audit entries can now be about an order (every step of a purchase), besides a book or an author.
ALTER TABLE audit_entry ADD COLUMN order_id BIGINT;
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_one_subject;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_one_subject
    CHECK (num_nonnulls(book_id, author_id, order_id) = 1);
CREATE INDEX audit_entry_order_idx ON audit_entry (order_id);
