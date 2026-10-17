package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
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
class BookController {

    private final BookService service;

    BookController(BookService service) {
        this.service = service;
    }

    /** Pages always use the given order; a {@code sort} request parameter is ignored. */
    static Pageable sortedBy(Pageable page, String property) {
        return PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by(property));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<BookResponse> create(@Valid @RequestBody BookRequest request) {
        BookResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/books/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    BookResponse update(@PathVariable BookId id, @Valid @RequestBody BookRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
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
