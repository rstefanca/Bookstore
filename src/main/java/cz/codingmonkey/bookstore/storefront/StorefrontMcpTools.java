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
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tools for finding books the way customers see them: catalog details together with the price and rating.
 */
@Component
class StorefrontMcpTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private static final BigDecimal FIVE = BigDecimal.valueOf(5);

    private final StorefrontService storefront;

    StorefrontMcpTools(StorefrontService storefront) {
        this.storefront = storefront;
    }

    @McpTool(name = "search_books", title = "Search books", description = """
            Searches the bookstore. All filters are optional and combine with AND; without filters it lists books \
            alphabetically. Returns books sorted by title unless orderBy is rating, each with id, title, authors \
            (credited order), genres, ISBN, publication year, description, price ({amount, currency}, or null if \
            the book isn't for sale) and rating ({average, count, stars}; average is null if unrated, stars counts \
            the ratings per 1-5 stars). Use list_authors or list_genres to find ids for the authorId and genreId \
            filters. minPrice and maxPrice are inclusive and in the store currency (EUR); with either set, books \
            that aren't for sale are left out. For "under 13 EUR" use maxPrice 12.99. minRating keeps books whose \
            average rating, as shown with one decimal, is at least that (1-5); unrated books are left out. orderBy \
            rating lists the best rated first (a few ratings count for less than many) and unrated books last; \
            the default is title.""",
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
            @McpToolParam(required = false, description = "Lowest average rating, 1-5, e.g. 4")
            @Nullable BigDecimal minRating,
            @McpToolParam(required = false, description = "title (default) or rating (best rated first)")
            @Nullable String orderBy,
            @McpToolParam(required = false, description = "Maximum number of books, 1-50; default 20")
            @Nullable Integer limit) {
        var search = new BookSearch(blankToNull(title), blankToNull(author),
                authorId == null ? null : new AuthorId(authorId),
                genreId == null ? null : new GenreId(genreId));
        BookOrdering ordering = orderBy == null || orderBy.isBlank() ? BookOrdering.TITLE : BookOrdering.parse(orderBy);
        var page = ordering.pageOf(PageRequest.of(0, checkLimit(limit)));
        return storefront.findBooks(search, new PriceRange(minPrice, maxPrice), checkMinRating(minRating), ordering,
                page).getContent();
    }

    @McpTool(name = "get_book", title = "Get book", description = """
            Gets one book by id, with its price (null if it isn't for sale) and rating.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    StorefrontBook getBook(@McpToolParam(description = "The book's id") long bookId) {
        return storefront.findBook(new BookId(bookId));
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

    private static @Nullable BigDecimal checkMinRating(@Nullable BigDecimal minRating) {
        if (minRating != null && (minRating.compareTo(BigDecimal.ONE) < 0 || minRating.compareTo(FIVE) > 0)) {
            throw new IllegalArgumentException("minRating must be between 1 and 5, was " + minRating.toPlainString());
        }
        return minRating;
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
