package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/books")
@Tag(name = "Books")
class BookController {

    private final BookService service;

    BookController(BookService service) {
        this.service = service;
    }

    /** Pages always use the given order; a {@code sort} request parameter is ignored. */
    static Pageable sortedBy(Pageable page, String property) {
        return PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by(property));
    }

    /**
     * Adds a book to the catalog.
     * <p>
     * {@code Location} is the new book's URL. The book isn't for sale until it gets a price, and has no stock until
     * it is restocked.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "The new book")
    @ApiResponse(responseCode = "400",
            description = "Invalid fields, unknown or repeated author ids, or unknown genre ids")
    @ApiResponse(responseCode = "409", description = "Another book has this ISBN")
    ResponseEntity<BookResponse> create(@Valid @RequestBody BookRequest request) {
        BookResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/books/" + created.id())).body(created);
    }

    /** Replaces a book's details. Omitted optional fields (genres, year, description) are cleared. */
    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "The updated book")
    @ApiResponse(responseCode = "400",
            description = "Invalid fields, unknown or repeated author ids, or unknown genre ids")
    @ApiResponse(responseCode = "404", description = "No book with this id")
    @ApiResponse(responseCode = "409",
            description = "Another book has this ISBN, or the book was changed by another request at the same time")
    BookResponse update(@PathVariable BookId id, @Valid @RequestBody BookRequest request) {
        return service.update(id, request);
    }

    /**
     * Deletes a book. Its price is removed too (its price history and audit trail are kept). A book that is in
     * stock can't be deleted; withdraw the stock first.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No book with this id")
    @ApiResponse(responseCode = "409", description = "The book is in stock")
    void delete(@PathVariable BookId id) {
        service.delete(id);
    }

    /** An author or genre was deleted while being linked to this book. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail referenceRemovedConcurrently() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "An author or genre of the book was deleted in the meantime");
    }

    /** Another request changed the book in the meantime; the client should re-read and retry. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The book was changed at the same time by another request; reload it and retry");
    }
}
