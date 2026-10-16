-- Unordered set of an author's favorite genres. Deleting a genre simply drops it from authors' favorites.
CREATE TABLE author_favorite_genre (
    author_id BIGINT NOT NULL REFERENCES author (id) ON DELETE CASCADE,
    genre_id  BIGINT NOT NULL REFERENCES genre (id) ON DELETE CASCADE,
    PRIMARY KEY (author_id, genre_id)
);

CREATE INDEX author_favorite_genre_genre_id_idx ON author_favorite_genre (genre_id);
