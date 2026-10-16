package cz.codingmonkey.bookstore.bookcatalog.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenreRequest(@NotBlank @Size(max = 100) String name) {
}
