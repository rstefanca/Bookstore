-- An audit entry is about one subject of any type (LongId.typeName()), so a new kind needs no new column.
ALTER TABLE audit_entry ADD COLUMN subject_type VARCHAR(50);
ALTER TABLE audit_entry ADD COLUMN subject_id   BIGINT;
UPDATE audit_entry SET
    subject_type = CASE WHEN book_id   IS NOT NULL THEN 'BOOK'
                        WHEN author_id IS NOT NULL THEN 'AUTHOR'
                        ELSE 'ORDER' END,
    subject_id   = coalesce(book_id, author_id, order_id);
ALTER TABLE audit_entry ALTER COLUMN subject_type SET NOT NULL;
ALTER TABLE audit_entry ALTER COLUMN subject_id   SET NOT NULL;

DROP INDEX audit_entry_order_step_once;
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_one_subject;
ALTER TABLE audit_entry DROP COLUMN book_id, DROP COLUMN author_id, DROP COLUMN order_id;

-- "Everything about book 2" (which had no index before), and the check for an event delivered again.
CREATE INDEX audit_entry_subject_idx ON audit_entry (subject_type, subject_id, action, occurred_at);
