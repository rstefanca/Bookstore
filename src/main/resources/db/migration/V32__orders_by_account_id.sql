-- Carts and orders belong to an account id, not a username (a reused username must not inherit them).

-- Carts of usernames without an account are deleted with their lines: personal data of users who are gone.
DELETE FROM cart c
WHERE NOT EXISTS (SELECT 1 FROM account a WHERE lower(a.username) = lower(c.customer));

-- The old keys were case-sensitive, so "Bob" and "bob" could both have a cart. They become one cart (no timestamp
-- to tell which is newer: the lowest ctid wins) holding one line per book (likewise the lowest ctid). The foreign
-- key goes first, so deleting the second cart keeps its lines for the first one.
ALTER TABLE cart_line DROP CONSTRAINT cart_line_customer_fkey;
DELETE FROM cart_line l USING cart_line m
WHERE lower(l.customer) = lower(m.customer) AND l.book_id = m.book_id AND l.ctid > m.ctid;
DELETE FROM cart c USING cart d
WHERE lower(c.customer) = lower(d.customer) AND c.ctid > d.ctid;

ALTER TABLE cart ADD COLUMN customer_id BIGINT;
UPDATE cart c SET customer_id = a.id FROM account a WHERE lower(a.username) = lower(c.customer);
ALTER TABLE cart_line ADD COLUMN customer_id BIGINT;
UPDATE cart_line l SET customer_id = a.id FROM account a WHERE lower(a.username) = lower(l.customer);

ALTER TABLE cart_line DROP CONSTRAINT cart_line_pkey;
ALTER TABLE cart_line DROP COLUMN customer;
ALTER TABLE cart DROP CONSTRAINT cart_pkey;
ALTER TABLE cart DROP COLUMN customer;
ALTER TABLE cart ALTER COLUMN customer_id SET NOT NULL;
ALTER TABLE cart ADD PRIMARY KEY (customer_id);
ALTER TABLE cart_line ALTER COLUMN customer_id SET NOT NULL;
ALTER TABLE cart_line ADD PRIMARY KEY (customer_id, book_id);
ALTER TABLE cart_line ADD FOREIGN KEY (customer_id) REFERENCES cart (customer_id) ON DELETE CASCADE;

-- Customers of orders without an account get a placeholder, so no order is lost: a soft-deleted CUSTOMER account
-- "~deleted-<id>" nobody can sign in to ('{bcrypt}' + a hash that isn't valid bcrypt never matches). They are created
-- as "~tmp-<md5 of the name>" (fits the column for any username) to be matched below.
INSERT INTO account (username, password_hash, role, deleted_at)
SELECT '~tmp-' || md5(n), '{bcrypt}' || md5(random()::text), 'CUSTOMER', now()
FROM (SELECT DISTINCT lower(o.customer) AS n FROM customer_order o
      WHERE NOT EXISTS (SELECT 1 FROM account a WHERE lower(a.username) = lower(o.customer))) gone;

ALTER TABLE customer_order ADD COLUMN customer_id BIGINT;

UPDATE customer_order o SET customer_id = a.id
FROM account a
WHERE lower(a.username) = lower(o.customer) OR a.username = '~tmp-' || md5(lower(o.customer));

UPDATE account SET username = '~deleted-' || id WHERE username LIKE '~tmp-%';

DROP INDEX customer_order_customer_idx;
ALTER TABLE customer_order DROP COLUMN customer;
ALTER TABLE customer_order ALTER COLUMN customer_id SET NOT NULL;
-- A customer's orders, newest first; also whether an account has orders at all.
CREATE INDEX customer_order_customer_idx ON customer_order (customer_id, created_at DESC);
