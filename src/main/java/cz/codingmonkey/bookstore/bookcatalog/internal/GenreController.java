package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/genres")
class GenreController {

    private final GenreService service;

    GenreController(GenreService service) {
        this.service = service;
    }

    @GetMapping
    PagedModel<GenreResponse> list(@RequestParam(required = false) String name, Pageable page) {
        return new PagedModel<>(service.findAll(name, BookController.sortedBy(page, "name")));
    }

    @GetMapping("/{id}")
    GenreResponse get(@PathVariable GenreId id) {
        return service.findById(id);
    }

    @PostMapping
    ResponseEntity<GenreResponse> create(@Valid @RequestBody GenreRequest request) {
        GenreResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/genres/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    GenreResponse update(@PathVariable GenreId id, @Valid @RequestBody GenreRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable GenreId id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** A concurrent request created the same name, or tagged a book with the genre being deleted. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void conflictingConcurrentChange() {
    }
}
