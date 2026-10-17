package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.GenreId;
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
@RequestMapping("/api/genres")
@Tag(name = "Genres", description = """
        Genres that books belong to and authors can favor. Names are unique regardless of case. Anyone can browse; \
        changes need `STAFF`.""")
class GenreController {

    private final GenreService service;

    GenreController(GenreService service) {
        this.service = service;
    }

    /**
     * Lists genres, sorted by name.
     *
     * @param name part of the name, in any case
     */
    @GetMapping
    @SecurityRequirements
    PagedModel<GenreResponse> list(@RequestParam(required = false) @Nullable String name, Pageable page) {
        return new PagedModel<>(service.findAll(name, BookController.sortedBy(page, "name")));
    }

    /** Returns a genre. */
    @GetMapping("/{id}")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "The genre")
    @ApiResponse(responseCode = "404", description = "No genre with this id")
    GenreResponse get(@PathVariable GenreId id) {
        return service.findById(id);
    }

    /**
     * Adds a genre.
     * <p>
     * {@code Location} is the new genre's URL.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "The new genre")
    @ApiResponse(responseCode = "409", description = "A genre with this name exists (in any case)")
    ResponseEntity<GenreResponse> create(@Valid @RequestBody GenreRequest request) {
        GenreResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/genres/" + created.id())).body(created);
    }

    /** Renames a genre. Books and authors show the new name right away. */
    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "The renamed genre")
    @ApiResponse(responseCode = "404", description = "No genre with this id")
    @ApiResponse(responseCode = "409", description = "Another genre has this name (in any case)")
    GenreResponse update(@PathVariable GenreId id, @Valid @RequestBody GenreRequest request) {
        return service.update(id, request);
    }

    /** Deletes a genre that no book has. It is removed from authors' favorites. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No genre with this id")
    @ApiResponse(responseCode = "409", description = "Books still have this genre")
    void delete(@PathVariable GenreId id) {
        service.delete(id);
    }

    /** A concurrent request created the same name, or tagged a book with the genre being deleted. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflictingConcurrentChange() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Another request took the genre name or assigned the genre to a book in the meantime");
    }
}
