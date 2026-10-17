package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Prices", description = """
        Each book's list price, in the store currency (EUR). A book without a price is in the catalog but not for \
        sale. Anyone can read current prices; setting them and reading the price history need `STAFF`.""")
class PricingController {

    private final PricingService service;

    PricingController(PricingService service) {
        this.service = service;
    }

    /** Returns a book's current price. */
    @GetMapping("/api/books/{bookId}/price")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "The price")
    @ApiResponse(responseCode = "404", description = "The book has no price (isn't for sale), or doesn't exist")
    PriceResponse get(@PathVariable BookId bookId) {
        return service.findByBookId(bookId);
    }

    /** Sets or changes a book's price, which puts it on sale. The change is recorded in the price history. */
    @PutMapping("/api/books/{bookId}/price")
    @ApiResponse(responseCode = "200", description = "The new price")
    @ApiResponse(responseCode = "400", description = """
            The amount isn't positive or has more than 2 decimal places, or the currency isn't the store currency""")
    @ApiResponse(responseCode = "404", description = "No book with this id")
    @ApiResponse(responseCode = "409", description = "The price was changed by another request at the same time")
    PriceResponse set(@PathVariable BookId bookId, @Valid @RequestBody PriceRequest request) {
        return service.setPrice(bookId, request.amount(), request.currency());
    }

    /** Removes a book's price, which takes it off sale. The price history is kept. */
    @DeleteMapping("/api/books/{bookId}/price")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Removed; the book is no longer for sale")
    @ApiResponse(responseCode = "404", description = "The book has no price")
    void remove(@PathVariable BookId bookId) {
        service.removePrice(bookId);
    }

    /**
     * Lists the periods during which a book had each price, newest first.
     * <p>
     * The current price's period has no {@code until}. A gap between periods means the book wasn't for sale. The
     * list is empty if the book never had a price, and is kept after the book is deleted.
     */
    @GetMapping("/api/books/{bookId}/price-history")
    PagedModel<PricePeriodResponse> history(@PathVariable BookId bookId, Pageable page) {
        return new PagedModel<>(service.findHistory(bookId, page));
    }

    /**
     * Lists the current prices of books for sale, sorted by book id.
     * <p>
     * Books that aren't for sale are left out.
     *
     * @param bookIds only these books, e.g. {@code ?bookId=1&bookId=2} to show prices next to a page of catalog
     *                results
     */
    @GetMapping("/api/prices")
    @SecurityRequirements
    PagedModel<PriceResponse> list(@RequestParam(name = "bookId", required = false) @Nullable List<BookId> bookIds,
            Pageable page) {
        var byBookId = PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("bookId"));
        return new PagedModel<>(service.findAll(bookIds, byBookId));
    }

    /**
     * A concurrent change won the race (a version conflict, or two first prices for the same book); the client
     * should re-read and retry.
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail concurrentModification() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The price was changed at the same time by another request; reload it and retry");
    }
}
