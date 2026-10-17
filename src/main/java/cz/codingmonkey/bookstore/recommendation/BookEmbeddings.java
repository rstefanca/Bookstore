package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores one embedding per book in {@code book_embedding} (pgvector) and finds nearest neighbours by cosine distance.
 * The stored title and authors aren't used for recommendations; they make the table readable when debugging.
 */
@Repository
class BookEmbeddings implements Recommendations {

    static final int DIMENSIONS = 384;

    private static final RowMapper<PickedBook> PICKED_BOOK = (rs, row) -> new PickedBook(
            new BookId(rs.getLong("book_id")), rs.getString("title"), toList(rs.getArray("authors")));

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

    @Override
    public Optional<List<Recommendation>> similarTo(BookId bookId, int limit) {
        return exists(bookId) ? Optional.of(findSimilar(bookId, limit)) : Optional.empty();
    }

    /** The books whose embeddings are closest to the given book's, most similar first, excluding the book itself. */
    List<Recommendation> findSimilar(BookId bookId, int limit) {
        // The target vector is an uncorrelated scalar subquery, evaluated once, so the ORDER BY can use the HNSW
        // index. <=> is pgvector's cosine distance; similarity = 1 - distance.
        String target = "(SELECT embedding FROM book_embedding WHERE book_id = ? AND NOT deleted)";
        return jdbc.query("""
                SELECT book_id, 1 - (embedding <=> %1$s) AS similarity
                FROM book_embedding
                WHERE NOT deleted AND book_id <> ?
                ORDER BY embedding <=> %1$s
                LIMIT ?
                """.formatted(target), (rs, row) -> new Recommendation(
                        new BookId(rs.getLong("book_id")), rs.getDouble("similarity")),
                bookId.value(), bookId.value(), bookId.value(), limit);
    }

    /** Whether any of the books has an embedding (not deleted). */
    boolean anyExists(Collection<BookId> ids) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM book_embedding WHERE book_id = ANY(?) AND NOT deleted)",
                Boolean.class, (Object) toArray(ids)));
    }

    /**
     * Books closest to the average embedding of the liked books, nearest first, leaving out the excluded books; a
     * window of {@code count} starting at {@code offset}. At least one liked book must have an embedding
     * ({@link #anyExists}), otherwise there is nothing to compare to.
     */
    @Transactional(readOnly = true)
    List<PickedBook> nearestTo(Collection<BookId> liked, Collection<BookId> excluded, int offset, int count) {
        // An HNSW index scan stops after hnsw.ef_search rows, so filtering out excluded books and skipping an offset
        // could silently return too few. Iterative scans keep going (in exact distance order) until the LIMIT is
        // filled. SET LOCAL lasts until the end of the transaction, which is why this method has its own.
        jdbc.execute("SET LOCAL hnsw.iterative_scan = strict_order");
        // The taste vector is an uncorrelated scalar subquery, evaluated once, so the ORDER BY can use the index.
        String taste = "(SELECT avg(embedding) FROM book_embedding WHERE book_id = ANY(?) AND NOT deleted)";
        return jdbc.query("""
                SELECT book_id, title, authors
                FROM book_embedding
                WHERE NOT deleted AND book_id <> ALL(?)
                ORDER BY embedding <=> %s
                OFFSET ? LIMIT ?
                """.formatted(taste), PICKED_BOOK, toArray(excluded), toArray(liked), offset, count);
    }

    /** The books that have an embedding (not deleted), in the given order; the others are left out. */
    List<PickedBook> findLive(List<BookId> ids) {
        Map<BookId, PickedBook> found = jdbc.query(
                        "SELECT book_id, title, authors FROM book_embedding WHERE book_id = ANY(?) AND NOT deleted",
                        PICKED_BOOK, (Object) toArray(ids))
                .stream()
                .collect(Collectors.toMap(PickedBook::bookId, Function.identity()));
        return ids.stream().filter(found::containsKey).map(found::get).toList();
    }

    private static Long[] toArray(Collection<BookId> ids) {
        return ids.stream().map(BookId::value).toArray(Long[]::new);
    }

    private static String toVectorLiteral(float[] embedding) {
        return IntStream.range(0, embedding.length)
                .mapToObj(i -> Float.toString(embedding[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
