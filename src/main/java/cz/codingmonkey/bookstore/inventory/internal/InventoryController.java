package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
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
class InventoryController {

    private final InventoryService service;

    InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping
    PagedModel<InventoryResponse> list(Pageable page) {
        // Pages are always ordered by book id; a sort request parameter is ignored.
        return new PagedModel<>(
                service.findAll(PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("bookId"))));
    }

    @GetMapping("/{bookId}")
    InventoryResponse get(@PathVariable BookId bookId) {
        return service.findByBookId(bookId);
    }

    @PostMapping("/{bookId}/restock")
    InventoryResponse restock(@PathVariable BookId bookId, @Valid @RequestBody StockRequest request) {
        return service.restock(bookId, request.quantity());
    }

    @PostMapping("/{bookId}/withdraw")
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
