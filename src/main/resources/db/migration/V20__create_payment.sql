-- Owned by the payment module (fake payments for the demo).
CREATE TABLE payment (
    reference  BIGINT PRIMARY KEY,
    amount     NUMERIC(12, 2)           NOT NULL,
    currency   VARCHAR(3)               NOT NULL,
    status     VARCHAR(20)              NOT NULL CHECK (status IN ('CHARGED', 'DECLINED', 'REFUNDED')),
    reason     VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
