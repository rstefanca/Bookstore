package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletionPolicy;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.bookcatalog.BookUpdatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import cz.codingmonkey.bookstore.shared.CurrentUser;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookService {

    private static final ChangeLog changesLog = ChangeLog.of(BookService.class);

    private final BookRepository repository;
    private final AuthorRepository authors;
    private final GenreLookup genres;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<BookDeletionPolicy> deletionPolicies;

    BookService(BookRepository repository,
                       AuthorRepository authors,
                       GenreLookup genres,
                       ApplicationEventPublisher events,
                       ObjectProvider<BookDeletionPolicy> deletionPolicies) {
        this.repository = repository;
        this.authors = authors;
        this.genres = genres;
        this.events = events;
        this.deletionPolicies = deletionPolicies;
    }

    /** Lists books matching the search, in the page's order and size; use {@link Pageable#unpaged} for all. */
    public Page<BookResponse> findAll(BookSearch search, Pageable page) {
        // The search's own order is part of its specification; a sorted page would replace it.
        Pageable paging = search.order() == null || page.isUnpaged() ? page
                : PageRequest.of(page.getPageNumber(), page.getPageSize());
        Page<Book> books = repository.findAll(BookSpecifications.matching(search), paging);
        return new PageImpl<>(toResponses(books.getContent()), books.getPageable(), books.getTotalElements());
    }

    @Cacheable(cacheNames = CatalogCaches.BOOKS, key = "#id")
    public BookResponse findById(BookId id) {
        return toResponses(List.of(get(id))).getFirst();
    }

    @Transactional
    public BookResponse create(BookRequest request) {
        if (repository.existsByIsbn(request.isbn())) {
            throw new DuplicateIsbnException(request.isbn());
        }
        Map<AuthorId, Author> bookAuthors = resolveAuthors(request.authorIds());
        Map<GenreId, Genre> bookGenres = genres.resolve(request.genreIds());
        Book book = repository.save(
                new Book(request.title(), request.authorIds(), request.genreIds(), request.isbn(),
                        request.publishedYear(), request.description()));
        events.publishEvent(new BookCreatedEvent(book.getId(), book.getTitle(), authorNames(book, bookAuthors),
                genreNames(bookGenres), book.getIsbn(), book.getDescription(), book.getVersion(),
                CurrentUser.username()));
        changesLog.info("Created book {} \"{}\" (ISBN {})", book.getId(), book.getTitle(), book.getIsbn());
        return BookResponse.from(book, bookAuthors, bookGenres);
    }

    @Transactional
    @CacheEvict(cacheNames = CatalogCaches.BOOKS, key = "#id")
    public BookResponse update(BookId id, BookRequest request) {
        Book book = get(id);
        if (!book.getIsbn().equals(request.isbn()) && repository.existsByIsbn(request.isbn())) {
            throw new DuplicateIsbnException(request.isbn());
        }
        Map<AuthorId, Author> bookAuthors = resolveAuthors(request.authorIds());
        Map<GenreId, Genre> bookGenres = genres.resolve(request.genreIds());
        book.update(request.title(), request.authorIds(), request.genreIds(), request.isbn(),
                request.publishedYear(), request.description());
        repository.flush(); // increments the version for the event (and detects concurrent updates)
        events.publishEvent(new BookUpdatedEvent(book.getId(), book.getTitle(), authorNames(book, bookAuthors),
                genreNames(bookGenres), book.getIsbn(), book.getDescription(), book.getVersion()));
        changesLog.info("Updated book {} \"{}\" (revision {})", book.getId(), book.getTitle(), book.getVersion());
        return BookResponse.from(book, bookAuthors, bookGenres);
    }

    @Transactional
    @CacheEvict(cacheNames = CatalogCaches.BOOKS, key = "#id")
    public void delete(BookId id) {
        // Lock first so no other module can change what the policies are about to check.
        Book book = repository.findAndLockById(id.value()).orElseThrow(() -> new BookNotFoundException(id));
        deletionPolicies.orderedStream()
                .flatMap(policy -> policy.vetoReason(id).stream())
                .findFirst()
                .ifPresent(reason -> {
                    throw new BookDeletionVetoedException(reason);
                });
        repository.delete(book);
        events.publishEvent(new BookDeletedEvent(book.getId(), book.getIsbn(), CurrentUser.username()));
        changesLog.info("Deleted book {} \"{}\" (ISBN {})", book.getId(), book.getTitle(), book.getIsbn());
    }

    private static List<String> authorNames(Book book, Map<AuthorId, Author> authors) {
        return book.getAuthorIds().stream().map(id -> authors.get(id).getName()).toList();
    }

    /** @param genres exactly the book's genres */
    private static List<String> genreNames(Map<GenreId, Genre> genres) {
        return genres.values().stream().map(Genre::getName).sorted().toList();
    }

    private List<BookResponse> toResponses(List<Book> books) {
        var authorIds = books.stream().flatMap(book -> book.getAuthorIds().stream()).distinct().toList();
        Map<AuthorId, Author> bookAuthors = authors.findAllById(authorIds).stream()
                .collect(Collectors.toMap(Author::getId, Function.identity()));
        var genreIds = books.stream().flatMap(book -> book.getGenreIds().stream()).distinct().toList();
        Map<GenreId, Genre> bookGenres = genres.findAll(genreIds);
        return books.stream().map(book -> BookResponse.from(book, bookAuthors, bookGenres)).toList();
    }

    /** Loads the requested authors, rejecting duplicates and ids that don't exist. */
    private Map<AuthorId, Author> resolveAuthors(List<AuthorId> authorIds) {
        if (new HashSet<>(authorIds).size() != authorIds.size()) {
            throw new InvalidAuthorsException("Author ids must not contain duplicates: " + authorIds);
        }
        Map<AuthorId, Author> found = authors.findAllById(authorIds).stream()
                .collect(Collectors.toMap(Author::getId, Function.identity()));
        List<AuthorId> unknown = authorIds.stream().filter(id -> !found.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidAuthorsException("Unknown author ids: " + unknown);
        }
        return found;
    }

    private Book get(BookId id) {
        return repository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
    }
}
