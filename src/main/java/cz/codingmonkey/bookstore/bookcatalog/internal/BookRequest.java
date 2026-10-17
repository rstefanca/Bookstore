package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A book's details, to create or replace it.
 *
 * @param authorIds   ids of existing authors, in credited order
 * @param genreIds    ids of existing genres; optional, defaults to none
 * @param isbn        unique in the catalog
 * @param description shown to customers and used for recommendations
 */
public record BookRequest(
        @Schema(example = "Dune") @NotBlank @Size(max = 255) String title,
        @Schema(example = "[1]") @NotEmpty @Size(max = 20) List<@NotNull AuthorId> authorIds,
        @Schema(example = "[1]") @Size(max = 10) Set<@NotNull GenreId> genreIds,
        @Schema(example = "9780441013593") @NotBlank @Size(max = 20) String isbn,
        @Schema(example = "1965") @PositiveOrZero @Nullable Integer publishedYear,
        @Schema(example = "Paul Atreides and the desert planet Arrakis.") @Size(max = 2000)
        @Nullable String description) {

    public BookRequest {
        genreIds = genreIds == null ? Set.of() : genreIds;
    }
}
