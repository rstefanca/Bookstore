CREATE TABLE genre (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

-- Genre names are unique regardless of case ("Fantasy" vs "fantasy").
CREATE UNIQUE INDEX genre_name_unique_idx ON genre (lower(name));

-- Unordered set of a book's genres.
CREATE TABLE book_genre (
    book_id  BIGINT NOT NULL REFERENCES book (id) ON DELETE CASCADE,
    genre_id BIGINT NOT NULL REFERENCES genre (id),
    PRIMARY KEY (book_id, genre_id)
);

CREATE INDEX book_genre_genre_id_idx ON book_genre (genre_id);

-- Starting set; maintained through /api/genres from here on.
INSERT INTO genre (name) VALUES
    ('Biography'),
    ('Business'),
    ('Children''s'),
    ('Classics'),
    ('Comedy'),
    ('Cookbooks'),
    ('Crime'),
    ('Drama'),
    ('Fantasy'),
    ('Graphic Novels'),
    ('Historical Fiction'),
    ('History'),
    ('Horror'),
    ('Literary Fiction'),
    ('Memoir'),
    ('Mystery'),
    ('Philosophy'),
    ('Poetry'),
    ('Romance'),
    ('Science'),
    ('Science Fiction'),
    ('Self-Help'),
    ('Thriller'),
    ('Travel'),
    ('Young Adult');
