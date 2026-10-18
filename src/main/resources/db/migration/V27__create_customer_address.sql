-- Owned by the customer module. Customers are referenced by username (accounts belong to the security module).
-- Each address group is all or nothing: either every column is set or none.
CREATE TABLE customer_address (
    customer             VARCHAR(100) PRIMARY KEY,
    shipping_name        VARCHAR(100),
    shipping_street      VARCHAR(200),
    shipping_city        VARCHAR(100),
    shipping_postal_code VARCHAR(20),
    shipping_country     CHAR(2),
    invoice_name         VARCHAR(100),
    invoice_street       VARCHAR(200),
    invoice_city         VARCHAR(100),
    invoice_postal_code  VARCHAR(20),
    invoice_country      CHAR(2),
    CONSTRAINT customer_address_shipping_complete CHECK (
        num_nonnulls(shipping_name, shipping_street, shipping_city, shipping_postal_code, shipping_country) IN (0, 5)),
    CONSTRAINT customer_address_invoice_complete CHECK (
        num_nonnulls(invoice_name, invoice_street, invoice_city, invoice_postal_code, invoice_country) IN (0, 5))
);
