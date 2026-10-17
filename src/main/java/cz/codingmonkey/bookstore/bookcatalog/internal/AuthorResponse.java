package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * An author.
 *
 * @param favoriteGenres sorted by name
 */
public record AuthorResponse(AuthorId id, String name, List<GenreResponse> favoriteGenres) {

    /**
     * @param genres must contain every favorite genre of the author
     */
    static AuthorResponse from(Author author, Map<GenreId, Genre> genres) {
        List<GenreResponse> favorites = author.getFavoriteGenreIds().stream()
                .map(genres::get)
                .sorted(Comparator.comparing(Genre::getName))
                .map(GenreResponse::from)
                .toList();
        return new AuthorResponse(author.getId(), author.getName(), favorites);
    }
}
