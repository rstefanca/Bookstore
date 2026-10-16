-- Price history: the periods during which a book had a given price. The current price's period is open
-- (valid_until IS NULL). Gaps between periods are times when the book had no price, i.e. wasn't for sale.
CREATE TABLE book_price_period (
    id          BIGSERIAL PRIMARY KEY,
    book_id     BIGINT                   NOT NULL,
    amount      NUMERIC(12, 2)           NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)               NOT NULL,
    valid_from  TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE,
    CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

CREATE INDEX book_price_period_book_idx ON book_price_period (book_id, valid_from DESC);

-- At most one current price per book.
CREATE UNIQUE INDEX book_price_period_one_open_idx ON book_price_period (book_id) WHERE valid_until IS NULL;

-- Existing prices: their real start is unknown, so their history starts now.
INSERT INTO book_price_period (book_id, amount, currency, valid_from)
SELECT book_id, amount, currency, now() FROM book_price;
