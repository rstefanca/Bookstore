package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
@Tag(name = "Authors", description = """
        Authors, referenced by books in credited order. Anyone can browse; changes need `STAFF`. An author's \
        favorite genres are optional.""")
class AuthorController {

    private final AuthorService service;

    AuthorController(AuthorService service) {
        this.service = service;
    }

    /**
     * Lists authors, sorted by name.
     *
     * @param name part of the name, in any case
     */
    @GetMapping
    @SecurityRequirements
    PagedModel<AuthorResponse> list(@RequestParam(required = false) @Nullable String name, Pageable page) {
        return new PagedModel<>(service.findAll(name, BookController.sortedBy(page, "name")));
    }

    /** Returns an author with their favorite genres. */
    @GetMapping("/{id}")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "The author")
    @ApiResponse(responseCode = "404", description = "No author with this id")
    AuthorResponse get(@PathVariable AuthorId id) {
        return service.findById(id);
    }

    /**
     * Adds an author.
     * <p>
     * {@code Location} is the new author's URL. Books can then list them in {@code authorIds}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "The new author")
    @ApiResponse(responseCode = "400", description = "Invalid fields, or unknown favorite genre ids")
    ResponseEntity<AuthorResponse> create(@Valid @RequestBody AuthorRequest request) {
        AuthorResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/authors/" + created.id())).body(created);
    }

    /**
     * Replaces an author's name and favorite genres; omitted favorites are cleared. Books show the new name right
     * away.
     */
    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "The updated author")
    @ApiResponse(responseCode = "400", description = "Invalid fields, or unknown favorite genre ids")
    @ApiResponse(responseCode = "404", description = "No author with this id")
    AuthorResponse update(@PathVariable AuthorId id, @Valid @RequestBody AuthorRequest request) {
        return service.update(id, request);
    }

    /** Deletes an author who has no books. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No author with this id")
    @ApiResponse(responseCode = "409", description = "The author still has books")
    void delete(@PathVariable AuthorId id) {
        service.delete(id);
    }

    /** A book was linked to the author while it was being deleted. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail authorStillReferenced() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A book was linked to the author in the meantime");
    }
}
