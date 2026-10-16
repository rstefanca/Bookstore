-- Staff accounts (customers will be handled separately). Passwords are stored as encoder-tagged hashes,
-- e.g. "{bcrypt}$2a$10$...".
CREATE TABLE account (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('STAFF', 'ADMIN'))
);

-- Usernames are unique regardless of case.
CREATE UNIQUE INDEX account_username_unique_idx ON account (lower(username));
