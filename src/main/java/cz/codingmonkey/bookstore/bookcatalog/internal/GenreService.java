package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GenreService {

    private static final ChangeLog changes = ChangeLog.of(GenreService.class);

    private final GenreRepository genres;
    private final BookRepository books;

    public GenreService(GenreRepository genres, BookRepository books) {
        this.genres = genres;
        this.books = books;
    }

    /**
     * @param name optional: the name contains this text, ignoring case
     * @param page order and size of the result; use {@link Pageable#unpaged} for all
     */
    public Page<GenreResponse> findAll(@Nullable String name, Pageable page) {
        Page<Genre> result = name == null ? genres.findAllBy(page) : genres.findByNameContainingIgnoreCase(name, page);
        return result.map(GenreResponse::from);
    }

    public GenreResponse findById(GenreId id) {
        return GenreResponse.from(get(id));
    }

    /**
     * Names are unique regardless of case. The unique index on {@code lower(name)} catches concurrent duplicates,
     * which the controller reports as a conflict too.
     */
    @Transactional
    public GenreResponse create(GenreRequest request) {
        String name = request.name().strip();
        if (genres.existsByNameIgnoreCase(name)) {
            throw new DuplicateGenreException(name);
        }
        Genre genre = genres.save(new Genre(name));
        changes.info("Created genre {} \"{}\"", genre.getId(), genre.getName());
        return GenreResponse.from(genre);
    }

    /** Also clears cached books and authors: they show genre names. */
    @Transactional
    @CacheEvict(cacheNames = {CatalogCaches.BOOKS, CatalogCaches.AUTHORS}, allEntries = true)
    public GenreResponse update(GenreId id, GenreRequest request) {
        Genre genre = get(id);
        String name = request.name().strip();
        if (genres.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new DuplicateGenreException(name);
        }
        String oldName = genre.getName();
        genre.rename(name);
        changes.info("Renamed genre {} from \"{}\" to \"{}\"", id, oldName, name);
        return GenreResponse.from(genre);
    }

    /**
     * Deletes a genre no book uses. A book tagged concurrently is caught by the {@code book_genre} foreign key.
     */
    @Transactional
    @CacheEvict(cacheNames = CatalogCaches.AUTHORS, allEntries = true) // the genre disappears from favorites
    public void delete(GenreId id) {
        Genre genre = get(id);
        if (books.existsByGenreIdsContaining(id)) {
            throw new GenreInUseException(id);
        }
        genres.delete(genre);
        changes.info("Deleted genre {} \"{}\"", id, genre.getName());
    }

    private Genre get(GenreId id) {
        return genres.findById(id).orElseThrow(() -> new GenreNotFoundException(id));
    }
}
