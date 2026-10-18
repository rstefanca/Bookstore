-- Deleting an account asks the audit log and the ratings whether they hold records of it, under the account row lock.
CREATE INDEX audit_entry_actor_idx ON audit_entry (actor_id);
CREATE INDEX book_rating_customer_idx ON book_rating (customer_id);
