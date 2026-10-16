package cz.codingmonkey.bookstore.bookcatalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Books and authors by id are served from the cache. To tell cached from fresh reads, the tests change rows directly
 * in the database, bypassing the services (and their evictions): a cached read still returns the old value.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogCachingIntegrationTest {

    @Autowired
    BookService books;

    @Autowired
    AuthorService authors;

    @Autowired
    GenreService genres;

    @Autowired
    CacheManager caches;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    AuthorId herbert;
    GenreId genre;
    BookId dune;

    @BeforeEach
    void setUp() {
        caches.getCacheNames().forEach(name -> caches.getCache(name).clear());
        genre = genres.create(new GenreRequest("Desert Planets " + UUID.randomUUID())).id();
        herbert = authors.create(new AuthorRequest("Frank Herbert", Set.of(genre))).id();
        dune = books.create(new BookRequest("Dune", List.of(herbert), Set.of(genre),
                "dune-" + UUID.randomUUID().toString().substring(0, 8), 1965, null)).id();
    }

    @Test
    void cachesBooksById() {
        assertThat(books.findById(dune).title()).isEqualTo("Dune");

        changeTitleInDatabase(dune, "Changed behind the cache's back");

        assertThat(books.findById(dune).title()).as("served from the cache").isEqualTo("Dune");
    }

    @Test
    void cachesAuthorsById() {
        assertThat(authors.findById(herbert).name()).isEqualTo("Frank Herbert");

        jdbc.update("UPDATE author SET name = 'Changed' WHERE id = ?", herbert.value());

        assertThat(authors.findById(herbert).name()).as("served from the cache").isEqualTo("Frank Herbert");
    }

    @Test
    void updatingABookEvictsIt() {
        books.findById(dune);

        books.update(dune, new BookRequest("Dune (Deluxe)", List.of(herbert), Set.of(genre), isbnOf(dune), 2019,
                null));

        assertThat(books.findById(dune).title()).isEqualTo("Dune (Deluxe)");
    }

    @Test
    void deletingABookEvictsIt() {
        books.findById(dune);

        books.delete(dune);

        assertThatThrownBy(() -> books.findById(dune)).isInstanceOf(BookNotFoundException.class);
    }

    @Test
    void renamingAnAuthorRefreshesTheirBooks() {
        books.findById(dune);
        authors.findById(herbert);

        authors.update(herbert, new AuthorRequest("Franklin Patrick Herbert", Set.of(genre)));

        assertThat(authors.findById(herbert).name()).isEqualTo("Franklin Patrick Herbert");
        assertThat(books.findById(dune).authors()).extracting(BookResponse.AuthorSummary::name)
                .containsExactly("Franklin Patrick Herbert");
    }

    @Test
    void renamingAGenreRefreshesBooksAndAuthors() {
        books.findById(dune);
        authors.findById(herbert);
        String renamed = "Sand and Spice " + UUID.randomUUID();

        genres.update(genre, new GenreRequest(renamed));

        assertThat(books.findById(dune).genres()).extracting(GenreResponse::name).containsExactly(renamed);
        assertThat(authors.findById(herbert).favoriteGenres()).extracting(GenreResponse::name)
                .containsExactly(renamed);
    }

    @Test
    void deletingAGenreRefreshesFavorites() {
        GenreId unused = genres.create(new GenreRequest("Unused " + UUID.randomUUID())).id();
        authors.update(herbert, new AuthorRequest("Frank Herbert", Set.of(genre, unused)));
        assertThat(authors.findById(herbert).favoriteGenres()).hasSize(2);

        genres.delete(unused);

        assertThat(authors.findById(herbert).favoriteGenres()).extracting(GenreResponse::id).containsExactly(genre);
    }

    @Test
    void evictsOnlyWhenTheTransactionCommits() {
        books.findById(dune);
        changeTitleInDatabase(dune, "Dune, changed directly");

        // An update that rolls back must not evict the cached entry...
        transaction.executeWithoutResult(status -> {
            books.update(dune, new BookRequest("Rolled back", List.of(herbert), Set.of(genre), isbnOf(dune), 1965,
                    null));
            status.setRollbackOnly();
        });
        assertThat(books.findById(dune).title()).as("still cached").isEqualTo("Dune");

        // ...while a committed one does.
        transaction.executeWithoutResult(status -> books.update(dune,
                new BookRequest("Committed", List.of(herbert), Set.of(genre), isbnOf(dune), 1965, null)));
        assertThat(books.findById(dune).title()).isEqualTo("Committed");
    }

    private void changeTitleInDatabase(BookId id, String title) {
        jdbc.update("UPDATE book SET title = ? WHERE id = ?", title, id.value());
    }

    private String isbnOf(BookId id) {
        return jdbc.queryForObject("SELECT isbn FROM book WHERE id = ?", String.class, id.value());
    }
}
