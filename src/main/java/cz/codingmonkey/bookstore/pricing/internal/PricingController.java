package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
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
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PricingController {

    private final PricingService service;

    PricingController(PricingService service) {
        this.service = service;
    }

    /** 404 means the book has no price, i.e. is not for sale (or doesn't exist). */
    @GetMapping("/api/books/{bookId}/price")
    PriceResponse get(@PathVariable BookId bookId) {
        return service.findByBookId(bookId);
    }

    @PutMapping("/api/books/{bookId}/price")
    PriceResponse set(@PathVariable BookId bookId, @Valid @RequestBody PriceRequest request) {
        return service.setPrice(bookId, request.amount(), request.currency());
    }

    @DeleteMapping("/api/books/{bookId}/price")
    ResponseEntity<Void> remove(@PathVariable BookId bookId) {
        service.removePrice(bookId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The book's price history, newest first; each period has a price, {@code from} and {@code until} ({@code null}
     * for the current price). Empty if the book never had a price.
     */
    @GetMapping("/api/books/{bookId}/price-history")
    PagedModel<PricePeriodResponse> history(@PathVariable BookId bookId, Pageable page) {
        return new PagedModel<>(service.findHistory(bookId, page));
    }

    /**
     * Prices of books for sale, by book id. {@code ?bookId=1&bookId=2} limits it to those books, e.g. to show prices
     * next to a page of catalog results.
     */
    @GetMapping("/api/prices")
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
