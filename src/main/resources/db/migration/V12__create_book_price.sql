-- Owned by the pricing module: the current list price of each book that is for sale.
CREATE TABLE book_price (
    book_id  BIGINT PRIMARY KEY,
    amount   NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3)     NOT NULL,
    version  BIGINT         NOT NULL
);
