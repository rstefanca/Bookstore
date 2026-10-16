package cz.codingmonkey.bookstore.bookcatalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The seeded genres stay in place; tests create genres with unique names instead of clearing the table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class GenreControllerIntegrationTest {

    @Autowired
    RestTestClient client;

    @Autowired
    BookRepository books;

    @Autowired
    AuthorRepository authors;

    @Autowired
    GenreRepository genres;

    @BeforeEach
    void cleanBooks() {
        books.deleteAll();
    }

    @Test
    void listsSeededGenresSortedByName() {
        client.get().uri("/api/genres?size=100")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[*].name").value(names -> assertThat(names).asInstanceOf(LIST)
                        .contains("Fantasy", "Science Fiction", "Children's", "Young Adult")
                        .isSortedAccordingTo((a, b) -> ((String) a).compareTo((String) b)));
    }

    @Test
    void createsFetchesAndRenamesGenre() {
        GenreResponse created = createGenre("  Solarpunk " + suffix() + "  ");
        assertThat(created.name()).as("trimmed").doesNotStartWith(" ").doesNotEndWith(" ");

        client.get().uri("/api/genres/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(GenreResponse.class).isEqualTo(created);

        String renamed = "Hopepunk " + suffix();
        client.put().uri("/api/genres/{id}", created.id())
                .body(new GenreRequest(renamed))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo(renamed);
    }

    @Test
    void renamingToSameNameWithDifferentCaseIsAllowed() {
        String name = "Cozy Mystery " + suffix();
        GenreResponse genre = createGenre(name);

        client.put().uri("/api/genres/{id}", genre.id())
                .body(new GenreRequest(name.toUpperCase()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void filtersByName() {
        client.get().uri("/api/genres?name=fiction")
                .exchange()
                .expectBody()
                .jsonPath("$.content[*].name").value(names -> assertThat(names).asInstanceOf(LIST)
                        .contains("Historical Fiction", "Literary Fiction", "Science Fiction")
                        .doesNotContain("Fantasy"));
    }

    @Test
    void rejectsDuplicateNameIgnoringCase() {
        client.post().uri("/api/genres")
                .body(new GenreRequest("  fantasy "))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        GenreResponse genre = createGenre("Weird Fiction " + suffix());
        client.put().uri("/api/genres/{id}", genre.id())
                .body(new GenreRequest("SCIENCE FICTION"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void databaseRejectsDuplicateNameIgnoringCaseEvenWithoutTheServiceCheck() {
        // What a concurrent create would hit after both requests passed the service's existence check.
        assertThatThrownBy(() -> genres.saveAndFlush(new Genre("FANTASY")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletesUnusedGenre() {
        GenreResponse genre = createGenre("Unused " + suffix());

        client.delete().uri("/api/genres/{id}", genre.id()).exchange().expectStatus().isNoContent();
        client.get().uri("/api/genres/{id}", genre.id()).exchange().expectStatus().isNotFound();
    }

    @Test
    void refusesToDeleteGenreInUse() {
        GenreResponse genre = createGenre("Space Opera " + suffix());
        AuthorId author = authors.save(new Author("Iain M. Banks")).getId();
        books.save(new Book("Consider Phlebas", List.of(author), Set.of(genre.id()), "9780316005388", 1987, null));

        client.delete().uri("/api/genres/{id}", genre.id())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void returns404ForMissingGenre() {
        client.get().uri("/api/genres/{id}", 999_999).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/genres/{id}", 999_999).exchange().expectStatus().isNotFound();
    }

    @Test
    void returns400ForBlankOrTooLongName() {
        client.post().uri("/api/genres").body(new GenreRequest(" ")).exchange().expectStatus().isBadRequest();
        client.post().uri("/api/genres").body(new GenreRequest("x".repeat(101))).exchange()
                .expectStatus().isBadRequest();
    }

    private GenreResponse createGenre(String name) {
        return client.post().uri("/api/genres")
                .body(new GenreRequest(name))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectBody(GenreResponse.class).returnResult().getResponseBody();
    }

    /** Keeps names unique across tests sharing the same database. */
    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
