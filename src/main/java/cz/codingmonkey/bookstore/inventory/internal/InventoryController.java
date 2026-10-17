package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
@Tag(name = "Inventory", description = """
        Copies in stock per book, maintained by staff (`STAFF`). A book has no stock record until it is first \
        restocked. Checkout takes ordered copies out of stock; a rejected or cancelled order puts them back.""")
class InventoryController {

    private final InventoryService service;

    InventoryController(InventoryService service) {
        this.service = service;
    }

    /** Lists stock levels, sorted by book id. */
    @GetMapping
    PagedModel<InventoryResponse> list(Pageable page) {
        // Pages are always ordered by book id; a sort request parameter is ignored.
        return new PagedModel<>(
                service.findAll(PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("bookId"))));
    }

    /** Returns a book's stock level. */
    @GetMapping("/{bookId}")
    @ApiResponse(responseCode = "200", description = "The stock level")
    @ApiResponse(responseCode = "404", description = "The book has never been restocked, or doesn't exist")
    InventoryResponse get(@PathVariable BookId bookId) {
        return service.findByBookId(bookId);
    }

    /** Adds copies to a book's stock; the first restock creates its stock record. Returns the new level. */
    @PostMapping("/{bookId}/restock")
    @ApiResponse(responseCode = "200", description = "The new stock level")
    @ApiResponse(responseCode = "404", description = "No book with this id")
    @ApiResponse(responseCode = "409", description = "The stock was changed by another request at the same time")
    InventoryResponse restock(@PathVariable BookId bookId, @Valid @RequestBody StockRequest request) {
        return service.restock(bookId, request.quantity());
    }

    /** Takes damaged or lost copies out of stock. Returns the new level. */
    @PostMapping("/{bookId}/withdraw")
    @ApiResponse(responseCode = "200", description = "The new stock level")
    @ApiResponse(responseCode = "404", description = "The book has no stock record")
    @ApiResponse(responseCode = "409",
            description = "Not that many copies in stock, or the stock was changed by another request at the same time")
    InventoryResponse withdraw(@PathVariable BookId bookId, @Valid @RequestBody StockRequest request) {
        return service.withdraw(bookId, request.quantity());
    }

    /**
     * A concurrent stock change won the race (a version conflict, or two first restocks inserting the same record);
     * the client should re-read and retry.
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail concurrentModification() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The stock was changed at the same time by another request; reload it and retry");
    }
}
