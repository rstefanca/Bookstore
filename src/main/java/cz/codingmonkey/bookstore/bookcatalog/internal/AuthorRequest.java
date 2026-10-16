package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * @param favoriteGenreIds ids of existing genres; optional, defaults to none
 */
public record AuthorRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 10) Set<@NotNull GenreId> favoriteGenreIds) {

    public AuthorRequest {
        favoriteGenreIds = favoriteGenreIds == null ? Set.of() : favoriteGenreIds;
    }
}
