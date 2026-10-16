CREATE TABLE inventory_item (
    book_id  BIGINT PRIMARY KEY,
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    version  BIGINT  NOT NULL
);
