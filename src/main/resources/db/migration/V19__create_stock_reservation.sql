-- Owned by the inventory module: stock taken for a reservation (e.g. an order), so it can be given back once.
CREATE TABLE stock_reservation (
    id         BIGINT PRIMARY KEY,
    status     VARCHAR(20)              NOT NULL CHECK (status IN ('RESERVED', 'FAILED', 'RELEASED')),
    reason     VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE stock_reservation_line (
    reservation_id BIGINT  NOT NULL REFERENCES stock_reservation (id) ON DELETE CASCADE,
    book_id        BIGINT  NOT NULL,
    quantity       INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (reservation_id, book_id)
);
