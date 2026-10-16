-- Who made the change: the username, or NULL for changes without a signed-in user (startup, background work)
-- and for entries recorded before actors were.
ALTER TABLE audit_entry ADD COLUMN actor VARCHAR(100);
