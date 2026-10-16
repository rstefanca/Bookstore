-- Audit entries are about either a book or an author.
ALTER TABLE audit_entry ALTER COLUMN book_id DROP NOT NULL;
ALTER TABLE audit_entry ADD COLUMN author_id BIGINT;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_one_subject CHECK (num_nonnulls(book_id, author_id) = 1);
