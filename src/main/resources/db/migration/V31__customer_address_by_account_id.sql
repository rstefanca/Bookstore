-- Saved addresses belong to an account id, not a username (a reused username must not inherit old addresses).
-- Addresses of usernames without an account are deleted: personal data of users who are gone.
DELETE FROM customer_address c
WHERE NOT EXISTS (SELECT 1 FROM account a WHERE lower(a.username) = lower(c.customer));

-- The old key was case-sensitive, so "Bob" and "bob" could both have saved addresses. There is no timestamp to
-- tell which is newer; keep one row (the lowest ctid).
DELETE FROM customer_address c USING customer_address d
WHERE lower(c.customer) = lower(d.customer) AND c.ctid > d.ctid;

ALTER TABLE customer_address ADD COLUMN customer_id BIGINT;

UPDATE customer_address c SET customer_id = a.id
FROM account a
WHERE lower(a.username) = lower(c.customer);

ALTER TABLE customer_address DROP CONSTRAINT customer_address_pkey;
ALTER TABLE customer_address DROP COLUMN customer;
ALTER TABLE customer_address ALTER COLUMN customer_id SET NOT NULL;
ALTER TABLE customer_address ADD PRIMARY KEY (customer_id);
