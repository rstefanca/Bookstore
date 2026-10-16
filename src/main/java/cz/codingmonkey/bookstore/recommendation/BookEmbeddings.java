package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stores one embedding per book in {@code book_embedding} (pgvector) and finds nearest neighbours by cosine distance.
 */
@Repository
class BookEmbeddings {

    static final int DIMENSIONS = 384;

    private final JdbcTemplate jdbc;

    BookEmbeddings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts or replaces a book's embedding, unless the stored one comes from the same or a newer revision, or the
     * book has been deleted.
     *
     * @return whether the row was written
     */
    boolean save(BookId bookId, long revision, String title, List<String> authors, float[] embedding) {
        if (embedding.length != DIMENSIONS) {
            throw new IllegalArgumentException(
                    "Expected an embedding with %d dimensions, got %d".formatted(DIMENSIONS, embedding.length));
        }
        int rows = jdbc.update("""
                INSERT INTO book_embedding (book_id, revision, title, authors, embedding)
                VALUES (?, ?, ?, ?, ?::vector)
                ON CONFLICT (book_id) DO UPDATE
                    SET revision = excluded.revision, title = excluded.title, authors = excluded.authors,
                        embedding = excluded.embedding
                    WHERE book_embedding.revision < excluded.revision AND NOT book_embedding.deleted
                """, ps -> {
            ps.setLong(1, bookId.value());
            ps.setLong(2, revision);
            ps.setString(3, title);
            ps.setArray(4, ps.getConnection().createArrayOf("text", authors.toArray()));
            ps.setString(5, toVectorLiteral(embedding));
        });
        return rows > 0;
    }

    /** Replaces the book's row with a tombstone, so it is never recommended and late events can't restore it. */
    void markDeleted(BookId bookId) {
        jdbc.update("""
                INSERT INTO book_embedding (book_id, revision, deleted) VALUES (?, 0, true)
                ON CONFLICT (book_id) DO UPDATE
                    SET deleted = true, title = NULL, authors = NULL, embedding = NULL
                """, bookId.value());
    }

    boolean exists(BookId bookId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM book_embedding WHERE book_id = ? AND NOT deleted)",
                Boolean.class, bookId.value()));
    }

    /** The books whose embeddings are closest to the given book's, most similar first, excluding the book itself. */
    List<SimilarBook> findSimilar(BookId bookId, int limit) {
        // The target vector is an uncorrelated scalar subquery, evaluated once, so the ORDER BY can use the HNSW
        // index. <=> is pgvector's cosine distance; similarity = 1 - distance.
        String target = "(SELECT embedding FROM book_embedding WHERE book_id = ? AND NOT deleted)";
        return jdbc.query("""
                SELECT book_id, title, authors, 1 - (embedding <=> %1$s) AS similarity
                FROM book_embedding
                WHERE NOT deleted AND book_id <> ?
                ORDER BY embedding <=> %1$s
                LIMIT ?
                """.formatted(target), (rs, row) -> new SimilarBook(
                        new BookId(rs.getLong("book_id")),
                        rs.getString("title"),
                        toList(rs.getArray("authors")),
                        rs.getDouble("similarity")),
                bookId.value(), bookId.value(), bookId.value(), limit);
    }

    private static String toVectorLiteral(float[] embedding) {
        return IntStream.range(0, embedding.length)
                .mapToObj(i -> Float.toString(embedding[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static List<String> toList(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
