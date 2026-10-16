CREATE TABLE book (
    id             BIGSERIAL PRIMARY KEY,
    title          VARCHAR(255) NOT NULL,
    author         VARCHAR(255) NOT NULL,
    isbn           VARCHAR(20)  NOT NULL UNIQUE,
    published_year INTEGER
);
