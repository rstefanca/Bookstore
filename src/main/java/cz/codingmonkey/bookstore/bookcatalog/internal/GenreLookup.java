package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Loads genres referenced by books and authors.
 */
@Component
public class GenreLookup {

    private final GenreRepository genres;

    GenreLookup(GenreRepository genres) {
        this.genres = genres;
    }

    /** Loads the genres with the given ids; unknown ids are left out. */
    Map<GenreId, Genre> findAll(Collection<GenreId> ids) {
        return genres.findAllById(Set.copyOf(ids)).stream()
                .collect(Collectors.toMap(Genre::getId, Function.identity()));
    }

    /** Loads the requested genres, rejecting ids that don't exist. */
    Map<GenreId, Genre> resolve(Set<GenreId> ids) {
        Map<GenreId, Genre> found = findAll(ids);
        List<GenreId> unknown = ids.stream()
                .filter(id -> !found.containsKey(id))
                .sorted(Comparator.comparingLong(GenreId::value))
                .toList();

        if (!unknown.isEmpty()) {
            throw new InvalidGenresException("Unknown genre ids: " + unknown);
        }

        return found;
    }
}
