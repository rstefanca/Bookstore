package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tool exposing similar-book recommendations to AI clients.
 */
@Component
class RecommendationMcpTools {

    private final BookEmbeddings embeddings;

    RecommendationMcpTools(BookEmbeddings embeddings) {
        this.embeddings = embeddings;
    }

    @McpTool(name = "find_similar_books", title = "Find similar books", description = """
            Recommends books similar to the given one, based on title, authors, genres and description, most \
            similar first. Each result has the book's id, title, authors and a similarity from -1 to 1 (higher is \
            more similar; above about 0.6 usually means a shared topic). Use get_book for full details.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    List<SimilarBook> findSimilarBooks(
            @McpToolParam(description = "Id of the book to find similar books for") long bookId,
            @McpToolParam(required = false, description = "Maximum number of books, 1-20; default 5")
            @Nullable Integer limit) {
        int max = limit == null ? 5 : limit;
        if (max < 1 || max > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20, was " + limit);
        }
        var id = new BookId(bookId);
        if (!embeddings.exists(id)) {
            throw new IllegalArgumentException("No recommendations for book " + bookId
                    + " (unknown book, or it was added moments ago and isn't indexed yet)");
        }
        return embeddings.findSimilar(id, max);
    }
}
