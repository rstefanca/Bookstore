package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tools for finding books the way customers see them: catalog details together with the price and
 * rating, and similar books to recommend.
 */
@Component
class StorefrontMcpTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final StorefrontService storefront;

    StorefrontMcpTools(StorefrontService storefront) {
        this.storefront = storefront;
    }

    @McpTool(name = "search_books", title = "Search books", description = """
            Searches the bookstore. All filters are optional and combine with AND; without filters it lists books \
            alphabetically. Returns books sorted by title, each with id, title, authors (credited order), genres, \
            ISBN, publication year, description, price ({amount, currency}, or null if the book isn't for sale) \
            and rating ({average, count, stars}; average is null if unrated, stars counts the ratings per 1-5 \
            stars). Use list_authors or list_genres to find ids for the authorId and genreId filters. minPrice and \
            maxPrice are inclusive and in the store currency (EUR); with either set, books that aren't for sale \
            are left out. For "under 13 EUR" use maxPrice 12.99.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    List<StorefrontBook> searchBooks(
            @McpToolParam(required = false, description = "Text the title contains, case-insensitive")
            @Nullable String title,
            @McpToolParam(required = false, description = "Text one of the authors' names contains, case-insensitive")
            @Nullable String author,
            @McpToolParam(required = false, description = "Only books by the author with this id")
            @Nullable Long authorId,
            @McpToolParam(required = false, description = "Only books in the genre with this id")
            @Nullable Long genreId,
            @McpToolParam(required = false, description = "Lowest price, inclusive, e.g. 10")
            @Nullable BigDecimal minPrice,
            @McpToolParam(required = false, description = "Highest price, inclusive, e.g. 12.99")
            @Nullable BigDecimal maxPrice,
            @McpToolParam(required = false, description = "Maximum number of books, 1-50; default 20")
            @Nullable Integer limit) {
        var search = new BookSearch(blankToNull(title), blankToNull(author),
                authorId == null ? null : new AuthorId(authorId),
                genreId == null ? null : new GenreId(genreId));
        var page = PageRequest.of(0, checkLimit(limit), Sort.by("title"));
        return storefront.findBooks(search, new PriceRange(minPrice, maxPrice), page).getContent();
    }

    @McpTool(name = "get_book", title = "Get book", description = """
            Gets one book by id, with its price (null if it isn't for sale) and rating.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    StorefrontBook getBook(@McpToolParam(description = "The book's id") long bookId) {
        return storefront.findBook(new BookId(bookId));
    }

    @McpTool(name = "find_similar_books", title = "Find similar books", description = """
            Recommends books similar to the given one that are for sale, based on title, authors, genres and \
            description, most similar first. Each result has the same fields as search_books (id, title, authors, \
            genres, ISBN, publication year, description, price and rating; price is never null here) plus a \
            similarity from -1 to 1 (higher is more similar; above about 0.6 usually means a shared topic). Books \
            that aren't for sale are left out, so there can be fewer results than the limit.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    List<SimilarBook> findSimilarBooks(
            @McpToolParam(description = "Id of the book to find similar books for") long bookId,
            @McpToolParam(required = false, description = "Maximum number of books, 1-20; default 5")
            @Nullable Integer limit) {
        int max = limit == null ? StorefrontController.DEFAULT_SIMILAR : limit;
        if (max < 1 || max > StorefrontController.MAX_SIMILAR) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + StorefrontController.MAX_SIMILAR + ", was " + limit);
        }
        return storefront.findSimilar(new BookId(bookId), max);
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
