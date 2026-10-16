-- Customers sign up themselves and get the CUSTOMER role (V14 named the inline check account_role_check).
ALTER TABLE account DROP CONSTRAINT account_role_check;
ALTER TABLE account ADD CONSTRAINT account_role_check CHECK (role IN ('STAFF', 'ADMIN', 'CUSTOMER'));
