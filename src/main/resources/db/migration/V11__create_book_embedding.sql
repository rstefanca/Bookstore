-- Requires the pgvector extension to be available on the PostgreSQL server.
CREATE EXTENSION IF NOT EXISTS vector;

-- Owned by the recommendation module: one embedding per book, kept in sync from book events.
CREATE TABLE book_embedding (
    book_id   BIGINT PRIMARY KEY,
    -- Book version the row was computed from; older events never overwrite newer ones.
    revision  BIGINT  NOT NULL,
    -- Tombstone: a deleted book keeps its row so late events can't bring it back.
    deleted   BOOLEAN NOT NULL DEFAULT false,
    title     VARCHAR(255),
    authors   TEXT[],
    -- all-MiniLM-L6-v2 embeddings have 384 dimensions.
    embedding vector(384),
    CHECK (deleted OR (title IS NOT NULL AND authors IS NOT NULL AND embedding IS NOT NULL))
);

CREATE INDEX book_embedding_embedding_idx ON book_embedding USING hnsw (embedding vector_cosine_ops);
