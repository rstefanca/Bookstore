package cz.codingmonkey.bookstore.bookcatalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class BookControllerIntegrationTest {

    @Autowired
    RestTestClient client;

    @Autowired
    BookRepository books;

    @Autowired
    AuthorRepository authors;

    @Autowired
    GenreRepository genres;

    AuthorId herbert;
    AuthorId gibson;
    AuthorId pratchett;
    AuthorId gaiman;

    // Seeded by the V7 migration.
    GenreId fantasy;
    GenreId comedy;
    GenreId scienceFiction;

    @BeforeEach
    void setUp() {
        books.deleteAll();
        authors.deleteAll();
        herbert = authors.save(new Author("Frank Herbert")).getId();
        gibson = authors.save(new Author("William Gibson")).getId();
        pratchett = authors.save(new Author("Terry Pratchett")).getId();
        gaiman = authors.save(new Author("Neil Gaiman")).getId();
        fantasy = seededGenre("Fantasy");
        comedy = seededGenre("Comedy");
        scienceFiction = seededGenre("Science Fiction");
    }

    private GenreId seededGenre(String name) {
        return genres.findAll().stream()
                .filter(genre -> genre.getName().equals(name))
                .findFirst().orElseThrow()
                .getId();
    }

    @Test
    void createsAndFetchesBook() {
        BookResponse created = client.post().uri("/api/books")
                .body(new BookRequest("Dune", List.of(herbert), Set.of(), "9780441013593", 1965,
                        "Paul Atreides and the desert planet Arrakis."))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectBody(BookResponse.class).returnResult().getResponseBody();

        assertThat(created.id()).isNotNull();
        assertThat(created.authors()).containsExactly(new BookResponse.AuthorSummary(herbert, "Frank Herbert"));
        assertThat(created.description()).isEqualTo("Paul Atreides and the desert planet Arrakis.");

        client.get().uri("/api/books/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(BookResponse.class).isEqualTo(created);

        // Typed ids are serialized as plain JSON numbers.
        client.get().uri("/api/books/{id}", created.id())
                .exchange()
                .expectBody()
                .jsonPath("$.id").isEqualTo(created.id().value())
                .jsonPath("$.authors[0].id").isEqualTo(herbert.value())
                .jsonPath("$.authors[0].name").isEqualTo("Frank Herbert")
                .jsonPath("$.authors[0].favoriteGenres").doesNotExist();
    }

    @Test
    void keepsCoAuthorsInCreditedOrder() {
        client.post().uri("/api/books")
                .body(new BookRequest("Good Omens", List.of(pratchett, gaiman), Set.of(), "9780060853983", 1990, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.authors[0].name").isEqualTo("Terry Pratchett")
                .jsonPath("$.authors[1].name").isEqualTo("Neil Gaiman");
    }

    @Test
    void createsBookWithGenresSortedByName() {
        client.post().uri("/api/books")
                .body(new BookRequest("Good Omens", List.of(pratchett, gaiman), Set.of(fantasy, comedy),
                        "9780060853983", 1990, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.genres.length()").isEqualTo(2)
                .jsonPath("$.genres[0].id").isEqualTo(comedy.value())
                .jsonPath("$.genres[0].name").isEqualTo("Comedy")
                .jsonPath("$.genres[1].name").isEqualTo("Fantasy");
    }

    @Test
    void genresAreOptional() {
        client.post().uri("/api/books")
                .header("Content-Type", "application/json")
                .body("""
                        {"title": "Dune", "authorIds": [%s], "isbn": "9780441013593"}
                        """.formatted(herbert))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.genres").isEmpty();
    }

    @Test
    void recordsAuditTrailViaAuditModule() {
        BookResponse created = client.post().uri("/api/books")
                .body(new BookRequest("Good Omens", List.of(pratchett, gaiman), Set.of(), "9780060853983", 1990, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookResponse.class).returnResult().getResponseBody();

        // The audit module handles BookCreatedEvent asynchronously, after the book transaction commits.
        await().untilAsserted(() -> client.get().uri("/api/audit?subjectType=BOOK&subjectId={id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].action").isEqualTo("BOOK_CREATED")
                .jsonPath("$.content[0].description")
                .isEqualTo("Created \"Good Omens\" by Terry Pratchett, Neil Gaiman (ISBN 9780060853983)"));
    }

    @Test
    void listsBooksFilteredByAuthorName() {
        books.save(new Book("Dune", List.of(herbert), Set.of(), "9780441013593", 1965, null));
        books.save(new Book("Neuromancer", List.of(gibson), Set.of(), "9780441569595", 1984, null));
        books.save(new Book("Good Omens", List.of(pratchett, gaiman), Set.of(), "9780060853983", 1990, null));

        client.get().uri("/api/books?author=gibson")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].title").isEqualTo("Neuromancer");

        // Matches on any co-author, and lists a book once even if several of its authors match.
        client.get().uri("/api/books?author=n")
                .exchange()
                .expectBody()
                .jsonPath("$.content[*].title").value(titles -> assertThat(titles).asInstanceOf(LIST)
                        .containsExactlyInAnyOrder("Dune", "Neuromancer", "Good Omens"));
    }

    @Test
    void listsBooksFilteredByTitleSortedByTitle() {
        books.save(new Book("Dune", List.of(herbert), Set.of(), "9780441013593", 1965, null));
        books.save(new Book("Children of Dune", List.of(herbert), Set.of(), "9780441104024", 1976, null));
        books.save(new Book("Neuromancer", List.of(gibson), Set.of(), "9780441569595", 1984, null));

        client.get().uri("/api/books?title=DUNE")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[*].title").value(titles -> assertThat(titles).asInstanceOf(LIST)
                        .containsExactly("Children of Dune", "Dune"));
    }

    @Test
    void listsBooksFilteredByAuthorId() {
        books.save(new Book("Dune", List.of(herbert), Set.of(), "9780441013593", 1965, null));
        books.save(new Book("Good Omens", List.of(pratchett, gaiman), Set.of(), "9780060853983", 1990, null));

        client.get().uri("/api/books?authorId={id}", gaiman)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].title").isEqualTo("Good Omens");
    }

    @Test
    void listsBooksFilteredByGenre() {
        books.save(new Book("Dune", List.of(herbert), Set.of(scienceFiction), "9780441013593", 1965, null));
        books.save(new Book("Good Omens", List.of(pratchett, gaiman), Set.of(fantasy, comedy), "9780060853983", 1990,
                null));

        client.get().uri("/api/books?genreId={id}", comedy)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].title").isEqualTo("Good Omens");
    }

    @Test
    void combinesFilters() {
        books.save(new Book("Dune", List.of(herbert), Set.of(scienceFiction), "9780441013593", 1965, null));
        books.save(new Book("Neuromancer", List.of(gibson), Set.of(scienceFiction), "9780441569595", 1984, null));
        books.save(new Book("Good Omens", List.of(pratchett, gaiman), Set.of(fantasy, comedy), "9780060853983", 1990,
                null));

        // "n" matches Herbert, Gibson and Gaiman; the genre narrows it down to the science fiction books.
        client.get().uri("/api/books?author=n&genreId={genre}", scienceFiction)
                .exchange()
                .expectBody()
                .jsonPath("$.content[*].title").value(titles -> assertThat(titles).asInstanceOf(LIST)
                        .containsExactlyInAnyOrder("Dune", "Neuromancer"));

        client.get().uri("/api/books?authorId={author}&genreId={genre}", gibson, scienceFiction)
                .exchange()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].title").isEqualTo("Neuromancer");

        client.get().uri("/api/books?authorId={author}&genreId={genre}", gibson, fantasy)
                .exchange()
                .expectBody().jsonPath("$.content").isEmpty();
    }

    @Test
    void replacesGenresOnUpdate() {
        Book book = books.save(
                new Book("Good Omens", List.of(pratchett, gaiman), Set.of(fantasy), "9780060853983", 1990, null));

        client.put().uri("/api/books/{id}", book.getId())
                .body(new BookRequest("Good Omens", List.of(pratchett, gaiman), Set.of(comedy), "9780060853983",
                        1990, null))
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/api/books/{id}", book.getId())
                .exchange()
                .expectBody()
                .jsonPath("$.genres.length()").isEqualTo(1)
                .jsonPath("$.genres[0].name").isEqualTo("Comedy");
    }

    @Test
    void returns400ForUnknownGenre() {
        client.post().uri("/api/books")
                .body(new BookRequest("Dune", List.of(herbert), Set.of(scienceFiction, new GenreId(999_999)),
                        "9780441013593", 1965, null))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(books.count()).isZero();
    }

    @Test
    void updatesBookIncludingAuthorOrder() {
        Book book = books.save(
                new Book("Good Omens", List.of(pratchett, gaiman), Set.of(), "9780060853983", 1990, null));

        client.put().uri("/api/books/{id}", book.getId())
                .body(new BookRequest("Good Omens (TV tie-in)", List.of(gaiman, pratchett), Set.of(), "9780060853983",
                        2019, "Illustrated edition."))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Good Omens (TV tie-in)")
                .jsonPath("$.authors[0].name").isEqualTo("Neil Gaiman")
                .jsonPath("$.authors[1].name").isEqualTo("Terry Pratchett")
                .jsonPath("$.publishedYear").isEqualTo(2019)
                .jsonPath("$.description").isEqualTo("Illustrated edition.");

        // Re-read in a fresh request to check the new order was persisted.
        client.get().uri("/api/books/{id}", book.getId())
                .exchange()
                .expectBody()
                .jsonPath("$.authors[*].name").value(names -> assertThat(names).asInstanceOf(LIST)
                        .containsExactly("Neil Gaiman", "Terry Pratchett"));
    }

    @Test
    void deletesBook() {
        Book book = books.save(new Book("Dune", List.of(herbert), Set.of(), "9780441013593", 1965, null));

        client.delete().uri("/api/books/{id}", book.getId())
                .exchange()
                .expectStatus().isNoContent();

        assertThat(books.existsById(book.getId())).isFalse();
        assertThat(authors.existsById(herbert)).as("authors outlive their books").isTrue();
    }

    @Test
    void returns404ForMissingBook() {
        client.get().uri("/api/books/{id}", 999_999)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void returns400ForUnknownAuthor() {
        client.post().uri("/api/books")
                .body(new BookRequest("Dune", List.of(herbert, new AuthorId(999_999)), Set.of(), "9780441013593", 1965,
                        null))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(books.count()).isZero();
    }

    @Test
    void returns400ForDuplicateAuthor() {
        client.post().uri("/api/books")
                .body(new BookRequest("Dune", List.of(herbert, herbert), Set.of(), "9780441013593", 1965, null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void returns400ForBookWithoutAuthors() {
        client.post().uri("/api/books")
                .body(new BookRequest("Dune", List.of(), Set.of(), "9780441013593", 1965, null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void returns400ForTooLongDescription() {
        client.post().uri("/api/books")
                .body(new BookRequest("Dune", List.of(herbert), Set.of(), "9780441013593", 1965, "x".repeat(2001)))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void returns400ForMalformedId() {
        client.get().uri("/api/books/abc").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/books/0").exchange().expectStatus().isBadRequest();
    }

    @Test
    void returns409ForDuplicateIsbn() {
        books.save(new Book("Dune", List.of(herbert), Set.of(), "9780441013593", 1965, null));

        client.post().uri("/api/books")
                .body(new BookRequest("Other", List.of(gibson), Set.of(), "9780441013593", 2000, null))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void returns400ForInvalidRequest() {
        client.post().uri("/api/books")
                .body(new BookRequest("", List.of(herbert), Set.of(), "", -1, null))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
