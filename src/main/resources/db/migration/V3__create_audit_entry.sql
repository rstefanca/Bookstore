CREATE TABLE audit_entry (
    id          BIGSERIAL PRIMARY KEY,
    action      VARCHAR(50)              NOT NULL,
    book_id     BIGINT                   NOT NULL,
    description VARCHAR(500)             NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
