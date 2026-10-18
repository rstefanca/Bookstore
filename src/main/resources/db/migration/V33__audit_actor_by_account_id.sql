-- Who made a change is an account id, not a username (a reused username must not inherit an audit trail).
-- Entries without an actor keep none. Actors without an account get a placeholder, as in V32: a soft-deleted CUSTOMER
-- account "~deleted-<id>" nobody can sign in to, created as "~tmp-<md5 of the name>" to be matched below.
INSERT INTO account (username, password_hash, role, deleted_at)
SELECT '~tmp-' || md5(n), '{bcrypt}' || md5(random()::text), 'CUSTOMER', now()
FROM (SELECT DISTINCT lower(e.actor) AS n FROM audit_entry e
      WHERE e.actor IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM account a WHERE lower(a.username) = lower(e.actor))) gone;

ALTER TABLE audit_entry ADD COLUMN actor_id BIGINT;

UPDATE audit_entry e SET actor_id = a.id
FROM account a
WHERE e.actor IS NOT NULL
  AND (lower(a.username) = lower(e.actor) OR a.username = '~tmp-' || md5(lower(e.actor)));

UPDATE account SET username = '~deleted-' || id WHERE username LIKE '~tmp-%';

ALTER TABLE audit_entry DROP COLUMN actor;
