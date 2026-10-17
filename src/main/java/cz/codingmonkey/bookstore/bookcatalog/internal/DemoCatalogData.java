package cz.codingmonkey.bookstore.bookcatalog.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import cz.codingmonkey.bookstore.shared.CurrentUser;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills an empty catalog with demo authors and books from {@code demo-data/catalog.json} when the {@code demo}
 * profile is active. It goes through the regular services, so the usual events fire: other modules compute
 * embeddings and record audit entries as for any other change. Runs in one transaction, so a failure leaves no
 * half-loaded catalog, and does nothing once the catalog has books or authors.
 */
@Component
@Profile("demo")
class DemoCatalogData implements ApplicationRunner {

    static final String LOCATION = "demo-data/catalog.json";

    /** Who the demo data's changes are attributed to in the audit log, as nobody is signed in at startup. */
    static final String ACTOR = "demo-data";

    private static final Logger log = LogManager.getLogger(DemoCatalogData.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(List<DemoAuthor> authors, List<DemoBook> books) {
    }

    /** @param key how books refer to the author in the file */
    record DemoAuthor(String key, String name, List<String> favoriteGenres) {
    }

    /** @param authors keys of the book's authors, in credited order */
    record DemoBook(String title, List<String> authors, List<String> genres, String isbn, Integer publishedYear,
            String description) {
    }

    private final BookRepository books;
    private final AuthorRepository authors;
    private final BookService bookService;
    private final AuthorService authorService;
    private final GenreService genreService;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    DemoCatalogData(BookRepository books, AuthorRepository authors, BookService bookService,
            AuthorService authorService, GenreService genreService, TransactionTemplate transaction,
            JsonMapper json) {
        this.books = books;
        this.authors = authors;
        this.bookService = bookService;
        this.authorService = authorService;
        this.genreService = genreService;
        this.transaction = transaction;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (books.count() > 0 || authors.count() > 0) {
            log.info("The catalog isn't empty, so no demo data was loaded");
            return;
        }
        Data data;
        try (InputStream in = new ClassPathResource(LOCATION).getInputStream()) {
            data = json.readValue(in, Data.class);
        }
        CurrentUser.runAs(ACTOR, () -> transaction.executeWithoutResult(status -> load(data)));
        log.info("Loaded demo data: {} authors, {} books", data.authors().size(), data.books().size());
    }

    private void load(Data data) {
        Map<String, GenreId> genres = genreService.findAll(null, Pageable.unpaged()).stream()
                .collect(Collectors.toMap(GenreResponse::name, GenreResponse::id));
        Map<String, AuthorId> authorIds = new HashMap<>();
        for (DemoAuthor author : data.authors()) {
            var request = new AuthorRequest(author.name(), genreIds(author.favoriteGenres(), genres));
            authorIds.put(author.key(), authorService.create(request).id());
        }
        for (DemoBook book : data.books()) {
            List<AuthorId> bookAuthors = book.authors().stream().map(key -> {
                AuthorId id = authorIds.get(key);
                if (id == null) {
                    throw new IllegalStateException(
                            "Demo book \"%s\" has unknown author \"%s\"".formatted(book.title(), key));
                }
                return id;
            }).toList();
            bookService.create(new BookRequest(book.title(), bookAuthors, genreIds(book.genres(), genres),
                    book.isbn(), book.publishedYear(), book.description()));
        }
    }

    /** Genres are matched by name; ones that no longer exist (e.g. renamed) are skipped with a warning. */
    private static Set<GenreId> genreIds(List<String> names, Map<String, GenreId> genres) {
        Set<GenreId> ids = new LinkedHashSet<>();
        for (String name : names) {
            GenreId id = genres.get(name);
            if (id == null) {
                log.warn("Demo data refers to unknown genre \"{}\"; skipping it", name);
            } else {
                ids.add(id);
            }
        }
        return ids;
    }
}
