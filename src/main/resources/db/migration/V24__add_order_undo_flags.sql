-- Whether the order process has seen the stock released / the payment refunded, so a result delivered again
-- (e.g. resubmitted after a crash) doesn't publish its undo step twice.
ALTER TABLE customer_order
    ADD COLUMN stock_released   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN payment_refunded BOOLEAN NOT NULL DEFAULT FALSE;
