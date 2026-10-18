-- Soft-deleted accounts stay (other modules keep records of them) but are anonymized: no names, no usable password,
-- username replaced by the tombstone "~deleted-<id>". NULL for live accounts.
ALTER TABLE account ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
