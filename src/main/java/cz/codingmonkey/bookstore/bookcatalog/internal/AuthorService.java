package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import cz.codingmonkey.bookstore.shared.CurrentUser;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthorService {

    private static final ChangeLog changes = ChangeLog.of(AuthorService.class);

    private final AuthorRepository authors;
    private final BookRepository books;
    private final GenreLookup genres;
    private final ApplicationEventPublisher events;

    public AuthorService(AuthorRepository authors, BookRepository books, GenreLookup genres,
            ApplicationEventPublisher events) {
        this.authors = authors;
        this.books = books;
        this.genres = genres;
        this.events = events;
    }

    /**
     * @param name optional: the name contains this text, ignoring case
     * @param page order and size of the result; use {@link Pageable#unpaged} for all
     */
    public Page<AuthorResponse> findAll(@Nullable String name, Pageable page) {
        Page<Author> result = name == null
                ? authors.findAllBy(page)
                : authors.findByNameContainingIgnoreCase(name, page);
        Map<GenreId, Genre> favorites = genres.findAll(
                result.stream().flatMap(author -> author.getFavoriteGenreIds().stream()).toList());
        return result.map(author -> AuthorResponse.from(author, favorites));
    }

    // cacheable?
    @Cacheable(cacheNames = CatalogCaches.AUTHORS, key = "#id")
    public AuthorResponse findById(AuthorId id) {
        Author author = get(id);
        return AuthorResponse.from(author, genres.findAll(author.getFavoriteGenreIds()));
    }

    @Transactional
    public AuthorResponse create(AuthorRequest request) {
        Map<GenreId, Genre> favorites = genres.resolve(request.favoriteGenreIds());
        Author author = authors.save(new Author(request.name(), request.favoriteGenreIds()));

        events.publishEvent(new AuthorCreatedEvent(author.getId(), author.getName(), CurrentUser.username()));
        changes.info("Created author {} \"{}\"", author.getId(), author.getName());

        return AuthorResponse.from(author, favorites);
    }

    /** Also clears cached books: they show their authors' names. */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CatalogCaches.AUTHORS, key = "#id"),
            @CacheEvict(cacheNames = CatalogCaches.BOOKS, allEntries = true)
    })
    public AuthorResponse update(AuthorId id, AuthorRequest request) {
        Author author = get(id);
        Map<GenreId, Genre> favorites = genres.resolve(request.favoriteGenreIds());
        author.update(request.name(), request.favoriteGenreIds());

        changes.info("Updated author {} \"{}\" (favorite genres {})", id, author.getName(),
                author.getFavoriteGenreIds());

        return AuthorResponse.from(author, favorites);
    }

    /**
     * Deletes an author without books. A book linked concurrently is caught by the {@code book_author} foreign key,
     * which the controller also reports as a conflict.
     */
    @Transactional
    @CacheEvict(cacheNames = CatalogCaches.AUTHORS, key = "#id") // only authors without books can be deleted
    public void delete(AuthorId id) {
        Author author = get(id);
        if (books.existsByAuthorIdsContaining(id)) {
            throw new AuthorHasBooksException(id);
        }
        authors.delete(author);
        changes.info("Deleted author {} \"{}\"", id, author.getName());
    }

    private Author get(AuthorId id) {
        return authors.findById(id).orElseThrow(() -> new AuthorNotFoundException(id));
    }
}
