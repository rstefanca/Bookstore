-- Ratings belong to an account id, not a username (a reused username must not inherit old ratings).
-- Raters without an account get a placeholder: a soft-deleted CUSTOMER account "~deleted-<id>" nobody can sign in to
-- ('{bcrypt}' + a hash that isn't valid bcrypt never matches). They are created as "~tmp-<md5 of the name>" (fits the column for any username) to be matched below.
INSERT INTO account (username, password_hash, role, deleted_at)
SELECT '~tmp-' || md5(n), '{bcrypt}' || md5(random()::text), 'CUSTOMER', now()
FROM (SELECT DISTINCT lower(r.customer) AS n FROM book_rating r
      WHERE NOT EXISTS (SELECT 1 FROM account a WHERE lower(a.username) = lower(r.customer))) gone;

ALTER TABLE book_rating ADD COLUMN customer_id BIGINT;

UPDATE book_rating r SET customer_id = a.id
FROM account a
WHERE lower(a.username) = lower(r.customer) OR a.username = '~tmp-' || md5(lower(r.customer));

UPDATE account SET username = '~deleted-' || id WHERE username LIKE '~tmp-%';

ALTER TABLE book_rating DROP CONSTRAINT book_rating_pkey;
ALTER TABLE book_rating DROP COLUMN customer;
ALTER TABLE book_rating ALTER COLUMN customer_id SET NOT NULL;
-- The old key was case-sensitive, so "Bob" and "bob" could both have rated a book; keep the newer rating.
DELETE FROM book_rating r USING book_rating s
WHERE r.book_id = s.book_id AND r.customer_id = s.customer_id
  AND (r.rated_at < s.rated_at OR (r.rated_at = s.rated_at AND r.ctid < s.ctid));

ALTER TABLE book_rating ADD PRIMARY KEY (book_id, customer_id);
