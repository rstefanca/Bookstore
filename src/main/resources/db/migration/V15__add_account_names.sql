-- Required for new accounts (enforced by the API); accounts created before this migration have none until an admin
-- or the account itself sets them.
ALTER TABLE account ADD COLUMN first_name VARCHAR(100);
ALTER TABLE account ADD COLUMN last_name VARCHAR(100);
