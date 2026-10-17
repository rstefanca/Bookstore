package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * An author's details, to create or replace them.
 *
 * @param favoriteGenreIds ids of existing genres; optional, defaults to none
 */
public record AuthorRequest(
        @Schema(example = "Frank Herbert") @NotBlank @Size(max = 255) String name,
        @Schema(example = "[1]") @Size(max = 10) Set<@NotNull GenreId> favoriteGenreIds) {

    public AuthorRequest {
        favoriteGenreIds = favoriteGenreIds == null ? Set.of() : favoriteGenreIds;
    }
}
