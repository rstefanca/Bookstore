package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-facing reads of books, with prices. Changes to books stay with the catalog's API.
 */
@RestController
@RequestMapping("/api/books")
@Tag(name = "Books", description = """
        The catalog's books with their current price. Anyone can browse; adding, changing and deleting books needs \
        `STAFF`. A book is for sale while it has a price (see Prices); stock is kept separately (see Inventory).""")
class StorefrontController {

    private final StorefrontService service;

    StorefrontController(StorefrontService service) {
        this.service = service;
    }

    /**
     * Lists books with their prices, sorted by title or by rating.
     * <p>
     * All filters are optional and combine with AND. A price bound leaves out books that aren't for sale, and a
     * minimum rating leaves out unrated books.
     *
     * @param title     part of the title, in any case
     * @param author    part of an author's name, in any case; matches any of the book's authors
     * @param authorId  only books by this author
     * @param genreId   only books in this genre
     * @param minPrice  lowest price, inclusive, in the store currency
     * @param maxPrice  highest price, inclusive, in the store currency
     * @param minRating lowest average rating, 1 to 5, compared with the average as shown (one decimal, so 3.95 counts
     *                  as 4.0); unrated books never match
     * @param orderBy   {@code title} (default), or {@code rating} for the best rated first: books rank by their
     *                  average with five ratings of the store-wide mean added, so a few ratings count for less than
     *                  many; unrated books come last. Ties are ordered by title. The {@code sort} parameter is ignored
     */
    @GetMapping
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "A page of books")
    @ApiResponse(responseCode = "400", description = """
            A price bound is negative, minPrice is above maxPrice, minRating isn't between 1 and 5, or orderBy \
            isn't title or rating""")
    PagedModel<StorefrontBook> list(@RequestParam(required = false) @Nullable String title,
            @RequestParam(required = false) @Nullable String author,
            @RequestParam(required = false) @Nullable AuthorId authorId,
            @RequestParam(required = false) @Nullable GenreId genreId,
            @RequestParam(required = false) @Nullable BigDecimal minPrice,
            @RequestParam(required = false) @Nullable BigDecimal maxPrice,
            @RequestParam(required = false) @Nullable @DecimalMin("1") @DecimalMax("5") BigDecimal minRating,
            @RequestParam(defaultValue = "title")
            @Parameter(schema = @Schema(allowableValues = {"title", "rating"}, defaultValue = "title"))
            String orderBy,
            Pageable page) {
        BookOrdering ordering = BookOrdering.parse(orderBy);
        return new PagedModel<>(service.findBooks(new BookSearch(title, author, authorId, genreId),
                new PriceRange(minPrice, maxPrice), minRating, ordering, ordering.pageOf(page)));
    }

    /** Returns a book with its price. */
    @GetMapping("/{id}")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "The book, with its price")
    @ApiResponse(responseCode = "404", description = "No book with this id")
    StorefrontBook get(@PathVariable BookId id) {
        return service.findBook(id);
    }
}
