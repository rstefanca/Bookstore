CREATE TABLE author (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

-- Ordered list of a book's authors. The unique constraint is deferred so Hibernate can reorder authors in place.
CREATE TABLE book_author (
    book_id   BIGINT  NOT NULL REFERENCES book (id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    author_id BIGINT  NOT NULL REFERENCES author (id),
    PRIMARY KEY (book_id, position),
    CONSTRAINT book_author_unique_author UNIQUE (book_id, author_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX book_author_author_id_idx ON book_author (author_id);

-- Migrate the former free-text author column: one author per distinct (trimmed) name, linked as sole author.
INSERT INTO author (name)
SELECT DISTINCT trim(author) FROM book ORDER BY 1;

INSERT INTO book_author (book_id, position, author_id)
SELECT b.id, 0, a.id
FROM book b
JOIN author a ON a.name = trim(b.author);

ALTER TABLE book DROP COLUMN author;
