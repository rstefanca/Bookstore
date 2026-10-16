package cz.codingmonkey.bookstore.bookcatalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class AuthorControllerIntegrationTest {

    @Autowired
    RestTestClient client;

    @Autowired
    BookRepository books;

    @Autowired
    AuthorRepository authors;

    @Autowired
    GenreRepository genres;

    @BeforeEach
    void cleanDatabase() {
        books.deleteAll();
        authors.deleteAll();
    }

    @Test
    void createsFetchesAndRenamesAuthor() {
        AuthorResponse created = client.post().uri("/api/authors")
                .body(new AuthorRequest("Ursula K. Le Guin", Set.of()))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectBody(AuthorResponse.class).returnResult().getResponseBody();

        client.get().uri("/api/authors/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthorResponse.class).isEqualTo(created);

        client.put().uri("/api/authors/{id}", created.id())
                .body(new AuthorRequest("Ursula Kroeber Le Guin", Set.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Ursula Kroeber Le Guin");
    }

    @Test
    void createsAuthorWithFavoriteGenresSortedByName() {
        GenreId horror = seededGenre("Horror");
        GenreId fantasy = seededGenre("Fantasy");

        AuthorResponse created = client.post().uri("/api/authors")
                .body(new AuthorRequest("Neil Gaiman", Set.of(horror, fantasy)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthorResponse.class).returnResult().getResponseBody();

        assertThat(created.favoriteGenres()).containsExactly(
                new GenreResponse(fantasy, "Fantasy"), new GenreResponse(horror, "Horror"));
        client.get().uri("/api/authors/{id}", created.id())
                .exchange()
                .expectBody(AuthorResponse.class).isEqualTo(created);
    }

    @Test
    void favoriteGenresAreOptional() {
        client.post().uri("/api/authors")
                .header("Content-Type", "application/json")
                .body("""
                        {"name": "Anonymous"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.favoriteGenres").isEmpty();
    }

    @Test
    void updateReplacesFavoriteGenres() {
        AuthorId author = authors.save(new Author("Ursula K. Le Guin", Set.of(seededGenre("Fantasy")))).getId();
        GenreId scienceFiction = seededGenre("Science Fiction");

        client.put().uri("/api/authors/{id}", author)
                .body(new AuthorRequest("Ursula K. Le Guin", Set.of(scienceFiction)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.favoriteGenres.length()").isEqualTo(1)
                .jsonPath("$.favoriteGenres[0].name").isEqualTo("Science Fiction");

        // PUT replaces the whole author: leaving the favorites out clears them.
        client.put().uri("/api/authors/{id}", author)
                .header("Content-Type", "application/json")
                .body("""
                        {"name": "Ursula K. Le Guin"}
                        """)
                .exchange()
                .expectStatus().isOk();
        client.get().uri("/api/authors/{id}", author)
                .exchange()
                .expectBody().jsonPath("$.favoriteGenres").isEmpty();
    }

    @Test
    void listsAuthorsWithTheirFavoriteGenres() {
        authors.save(new Author("Isaac Asimov", Set.of(seededGenre("Science Fiction"), seededGenre("Science"))));
        authors.save(new Author("Agatha Christie", Set.of(seededGenre("Mystery"))));

        client.get().uri("/api/authors")
                .exchange()
                .expectBody()
                .jsonPath("$.content[?(@.name == 'Isaac Asimov')].favoriteGenres[*].name").value(names ->
                        assertThat(names).asInstanceOf(LIST).containsExactly("Science", "Science Fiction"))
                .jsonPath("$.content[?(@.name == 'Agatha Christie')].favoriteGenres[*].name").value(names ->
                        assertThat(names).asInstanceOf(LIST).containsExactly("Mystery"));
    }

    @Test
    void returns400ForUnknownFavoriteGenre() {
        client.post().uri("/api/authors")
                .body(new AuthorRequest("Nobody", Set.of(new GenreId(999_999))))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(authors.count()).isZero();
    }

    @Test
    void deletingAGenreRemovesItFromFavorites() {
        GenreResponse genre = client.post().uri("/api/genres")
                .body(new GenreRequest("Dying Earth " + UUID.randomUUID().toString().substring(0, 8)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(GenreResponse.class).returnResult().getResponseBody();
        AuthorId author = authors.save(new Author("Jack Vance", Set.of(genre.id(), seededGenre("Fantasy")))).getId();

        client.delete().uri("/api/genres/{id}", genre.id()).exchange().expectStatus().isNoContent();

        client.get().uri("/api/authors/{id}", author)
                .exchange()
                .expectBody()
                .jsonPath("$.favoriteGenres.length()").isEqualTo(1)
                .jsonPath("$.favoriteGenres[0].name").isEqualTo("Fantasy");
    }

    @Test
    void auditsAuthorCreation() {
        AuthorResponse created = client.post().uri("/api/authors")
                .body(new AuthorRequest("Octavia E. Butler", Set.of()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthorResponse.class).returnResult().getResponseBody();

        // The audit module handles AuthorCreatedEvent asynchronously, after the author transaction commits.
        await().untilAsserted(() -> client.get().uri("/api/audit?authorId={id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].action").isEqualTo("AUTHOR_CREATED")
                .jsonPath("$.content[0].authorId").isEqualTo(created.id().value())
                .jsonPath("$.content[0].bookId").isEmpty()
                .jsonPath("$.content[0].description").isEqualTo("Created author \"Octavia E. Butler\""));
    }

    @Test
    void auditRejectsFilteringByBookAndAuthorAtOnce() {
        client.get().uri("/api/audit?bookId=1&authorId=1").exchange().expectStatus().isBadRequest();
    }

    @Test
    void renamedAuthorShowsUpOnTheirBooks() {
        AuthorId author = authors.save(new Author("Iain Banks")).getId();
        Book book = books.save(new Book("Consider Phlebas", List.of(author), Set.of(), "9780316005388", 1987, null));

        client.put().uri("/api/authors/{id}", author)
                .body(new AuthorRequest("Iain M. Banks", Set.of()))
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/api/books/{id}", book.getId())
                .exchange()
                .expectBody().jsonPath("$.authors[0].name").isEqualTo("Iain M. Banks");
    }

    @Test
    void listsAuthorsFilteredByName() {
        authors.save(new Author("Isaac Asimov"));
        authors.save(new Author("Arthur C. Clarke"));

        client.get().uri("/api/authors?name=asim")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].name").isEqualTo("Isaac Asimov");
    }

    @Test
    void deletesAuthorWithoutBooks() {
        AuthorId author = authors.save(new Author("Nobody")).getId();

        client.delete().uri("/api/authors/{id}", author).exchange().expectStatus().isNoContent();

        assertThat(authors.existsById(author)).isFalse();
    }

    @Test
    void refusesToDeleteAuthorWithBooks() {
        AuthorId author = authors.save(new Author("Frank Herbert")).getId();
        books.save(new Book("Dune", List.of(author), Set.of(), "9780441013593", 1965, null));

        client.delete().uri("/api/authors/{id}", author)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        assertThat(authors.existsById(author)).isTrue();
    }

    @Test
    void returns404ForMissingAuthor() {
        client.get().uri("/api/authors/{id}", 999_999).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/authors/{id}", 999_999).exchange().expectStatus().isNotFound();
    }

    @Test
    void returns400ForBlankName() {
        client.post().uri("/api/authors")
                .body(new AuthorRequest(" ", Set.of()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private GenreId seededGenre(String name) {
        return genres.findAll().stream()
                .filter(genre -> genre.getName().equals(name))
                .findFirst().orElseThrow()
                .getId();
    }
}
