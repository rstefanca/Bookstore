package cz.codingmonkey.bookstore.bookcatalog.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A genre's name, to create or rename it. */
public record GenreRequest(@Schema(example = "Science Fiction") @NotBlank @Size(max = 100) String name) {
}
