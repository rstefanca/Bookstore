-- Optimistic locking for books; also sent as the revision in book events.
ALTER TABLE book ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
