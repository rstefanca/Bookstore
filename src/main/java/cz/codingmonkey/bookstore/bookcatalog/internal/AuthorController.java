package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import jakarta.validation.Valid;
import java.net.URI;
import org.jspecify.annotations.Nullable;
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
@RequestMapping("/api/authors")
class AuthorController {

    private final AuthorService service;

    AuthorController(AuthorService service) {
        this.service = service;
    }

    @GetMapping
    PagedModel<AuthorResponse> list(@RequestParam(required = false) @Nullable String name, Pageable page) {
        return new PagedModel<>(service.findAll(name, BookController.sortedBy(page, "name")));
    }

    @GetMapping("/{id}")
    AuthorResponse get(@PathVariable AuthorId id) {
        return service.findById(id);
    }

    @PostMapping
    ResponseEntity<AuthorResponse> create(@Valid @RequestBody AuthorRequest request) {
        AuthorResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/authors/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    AuthorResponse update(@PathVariable AuthorId id, @Valid @RequestBody AuthorRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable AuthorId id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** A book was linked to the author while it was being deleted. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void authorStillReferenced() {
    }
}
