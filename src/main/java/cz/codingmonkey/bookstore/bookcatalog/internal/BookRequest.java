package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

/**
 * @param authorIds ids of existing authors, in credited order
 * @param genreIds  ids of existing genres; optional, defaults to none
 */
public record BookRequest(
        @NotBlank @Size(max = 255) String title,
        @NotEmpty @Size(max = 20) List<@NotNull AuthorId> authorIds,
        @Size(max = 10) Set<@NotNull GenreId> genreIds,
        @NotBlank @Size(max = 20) String isbn,
        @PositiveOrZero Integer publishedYear,
        @Size(max = 2000) String description) {

    public BookRequest {
        genreIds = genreIds == null ? Set.of() : genreIds;
    }
}
