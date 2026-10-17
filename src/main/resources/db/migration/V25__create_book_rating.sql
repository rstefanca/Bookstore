-- Owned by the rating module. Customers are referenced by username (accounts belong to the security module).
CREATE TABLE book_rating (
    book_id  BIGINT                   NOT NULL,
    customer VARCHAR(100)             NOT NULL,
    stars    SMALLINT                 NOT NULL CHECK (stars BETWEEN 1 AND 5),
    rated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (book_id, customer)
);
