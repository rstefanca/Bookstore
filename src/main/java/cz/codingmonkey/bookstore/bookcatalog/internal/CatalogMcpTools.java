package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tools for AI clients to look up authors and genres. Searching and getting books, with prices, is
 * the storefront's ({@code StorefrontMcpTools}). They delegate to the same services as the REST API.
 * Ids are plain numbers here, which language models handle more reliably than nested objects. Errors (unknown ids,
 * invalid limits) are returned to the client as tool errors with the exception message.
 */
@Component
class CatalogMcpTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final AuthorService authors;
    private final GenreService genres;

    CatalogMcpTools(AuthorService authors, GenreService genres) {
        this.authors = authors;
        this.genres = genres;
    }

    @McpTool(name = "list_authors", title = "List authors", description = """
            Lists authors sorted by name, each with id, name and favorite genres. Optionally filtered by name.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    List<AuthorResponse> listAuthors(
            @McpToolParam(required = false, description = "Text the name contains, case-insensitive")
            @Nullable String name,
            @McpToolParam(required = false, description = "Maximum number of authors, 1-50; default 20")
            @Nullable Integer limit) {
        return authors.findAll(blankToNull(name), PageRequest.of(0, checkLimit(limit), Sort.by("name"))).getContent();
    }

    @McpTool(name = "get_author", title = "Get author", description = "Gets one author by id.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    AuthorResponse getAuthor(@McpToolParam(description = "The author's id") long authorId) {
        return authors.findById(new AuthorId(authorId));
    }

    @McpTool(name = "list_genres", title = "List genres", description = """
            Lists all genres sorted by name, each with id and name. Optionally filtered by name.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    List<GenreResponse> listGenres(
            @McpToolParam(required = false, description = "Text the name contains, case-insensitive")
            @Nullable String name) {
        return genres.findAll(blankToNull(name), Pageable.unpaged(Sort.by("name"))).getContent();
    }

    private static int checkLimit(@Nullable Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ", was " + limit);
        }
        return limit;
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
