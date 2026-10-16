package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.pricing.Money;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tool exposing book prices to AI clients.
 */
@Component
class PricingMcpTools {

    private final PricingService pricing;

    PricingMcpTools(PricingService pricing) {
        this.pricing = pricing;
    }

    @McpTool(name = "get_book_prices", title = "Get book prices", description = """
            Gets the current prices of books, e.g. for books found with search_books. Returns one entry per \
            requested book id, in the requested order; "price" is null if the book has no price, which means it is \
            not for sale (or the id is unknown). Prices have an amount and an ISO 4217 currency code.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    List<PriceResponse> getBookPrices(@McpToolParam(description = "Ids of the books, at most 50") List<Long> bookIds) {
        if (bookIds.isEmpty() || bookIds.size() > 50) {
            throw new IllegalArgumentException("Pass between 1 and 50 book ids, got " + bookIds.size());
        }
        List<BookId> ids = bookIds.stream().map(BookId::new).toList();
        Map<BookId, Money> prices = pricing.findByBookIds(ids);
        return ids.stream().map(id -> new PriceResponse(id, prices.get(id))).toList();
    }
}
