-- Owned by the ordering module. Customers are referenced by username (accounts belong to the security module).

-- One cart per customer.
CREATE TABLE cart (
    customer   VARCHAR(100) PRIMARY KEY,
    version    BIGINT       NOT NULL
);

CREATE TABLE cart_line (
    customer VARCHAR(100) NOT NULL REFERENCES cart (customer) ON DELETE CASCADE,
    book_id  BIGINT       NOT NULL,
    quantity INTEGER      NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (customer, book_id)
);

CREATE TABLE customer_order (
    id         BIGSERIAL PRIMARY KEY,
    customer   VARCHAR(100)             NOT NULL,
    status     VARCHAR(20)              NOT NULL
        CHECK (status IN ('PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'REJECTED', 'CANCELLED')),
    total      NUMERIC(12, 2)           NOT NULL,
    currency   VARCHAR(3)               NOT NULL,
    reason     VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL
);

CREATE INDEX customer_order_customer_idx ON customer_order (customer, created_at DESC);

-- Titles and prices as they were at checkout.
CREATE TABLE order_line (
    order_id   BIGINT         NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    position   INTEGER        NOT NULL,
    book_id    BIGINT         NOT NULL,
    title      VARCHAR(255)   NOT NULL,
    quantity   INTEGER        NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2) NOT NULL,
    PRIMARY KEY (order_id, position)
);
