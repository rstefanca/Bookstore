-- A copy of the customer's addresses at checkout, so later edits don't change the order.
-- Nullable: orders placed before this migration have none. Each group is all or nothing.
ALTER TABLE customer_order
    ADD COLUMN shipping_name        VARCHAR(100),
    ADD COLUMN shipping_street      VARCHAR(200),
    ADD COLUMN shipping_city        VARCHAR(100),
    ADD COLUMN shipping_postal_code VARCHAR(20),
    ADD COLUMN shipping_country     CHAR(2),
    ADD COLUMN invoice_name         VARCHAR(100),
    ADD COLUMN invoice_street       VARCHAR(200),
    ADD COLUMN invoice_city         VARCHAR(100),
    ADD COLUMN invoice_postal_code  VARCHAR(20),
    ADD COLUMN invoice_country      CHAR(2),
    ADD CONSTRAINT customer_order_shipping_complete CHECK (
        num_nonnulls(shipping_name, shipping_street, shipping_city, shipping_postal_code, shipping_country) IN (0, 5)),
    ADD CONSTRAINT customer_order_invoice_complete CHECK (
        num_nonnulls(invoice_name, invoice_street, invoice_city, invoice_postal_code, invoice_country) IN (0, 5));
