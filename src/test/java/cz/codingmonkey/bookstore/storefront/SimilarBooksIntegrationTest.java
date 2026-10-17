package cz.codingmonkey.bookstore.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorResponse;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * End to end: books created through the catalog API become recommendable once their embeddings are computed, and
 * GET /api/books/{id}/similar shows those for sale with their prices and ratings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class SimilarBooksIntegrationTest {

    private static final String PASSWORD = "buyer-password-1";

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @LocalServerPort
    int port;

    BookId dune;
    BookId childrenOfDune;
    BookId duneMessiah;
    BookId cookbook;

    @BeforeEach
    void setUp() {
        // Start from a clean slate. Deleting directly in the database bypasses the book events, so the embeddings
        // have to be cleared as well.
        jdbc.update("DELETE FROM book");
        jdbc.update("DELETE FROM book_embedding");

        AuthorId herbert = createAuthor("Frank Herbert");
        dune = createBook("Dune", herbert, "9780441013593",
                "On the desert planet Arrakis, Paul Atreides fights for control of the spice melange.");
        childrenOfDune = createBook("Children of Dune", herbert, "9780441104024",
                "Paul's twin children inherit the desert world of Arrakis and its spice-fuelled empire.");
        duneMessiah = createBook("Dune Messiah", herbert, "9780441172696",
                "Twelve years on, Emperor Paul Atreides rules Arrakis and the spice, and fears his own prophecy.");
        cookbook = createBook("The Joy of Cooking", createAuthor("Irma S. Rombauer"), "9780743246262",
                "Classic American recipes for soups, breads, roasts and desserts.");
        // Dune Messiah has no price, so it isn't for sale and mustn't be recommended.
        setPrice(childrenOfDune, "9.99");
        setPrice(cookbook, "25.00");

        // Embeddings are computed asynchronously after each book is created.
        await().until(() -> jdbc.queryForObject("SELECT count(*) FROM book_embedding WHERE NOT deleted",
                Integer.class) == 4);
    }

    @Test
    void recommendsSimilarBooksForSaleWithPricesAndRatings() {
        rate(signUp(), childrenOfDune, 4);

        client.get().uri("/api/books/{id}/similar", dune)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo(childrenOfDune.value())
                .jsonPath("$[0].title").isEqualTo("Children of Dune")
                .jsonPath("$[0].authors[0].name").isEqualTo("Frank Herbert")
                .jsonPath("$[0].isbn").isEqualTo("9780441104024")
                .jsonPath("$[0].price.amount").isEqualTo(9.99)
                .jsonPath("$[0].price.currency").isEqualTo("EUR")
                .jsonPath("$[0].rating.count").isEqualTo(1)
                .jsonPath("$[0].rating.average").isEqualTo(4.0)
                .jsonPath("$[0].similarity").value(similarity -> assertThat((Double) similarity).isGreaterThan(0.6))
                .jsonPath("$[1].title").isEqualTo("The Joy of Cooking")
                .jsonPath("$[1].rating.count").isEqualTo(0);
    }

    @Test
    void recommendsBooksOnceTheyAreForSale() {
        setPrice(duneMessiah, "11.50");

        client.get().uri("/api/books/{id}/similar", dune)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].title").value(titles -> assertThat(titles).asInstanceOf(LIST)
                        .containsExactlyInAnyOrder("Children of Dune", "Dune Messiah", "The Joy of Cooking"))
                .jsonPath("$[2].title").isEqualTo("The Joy of Cooking");
    }

    @Test
    void recommendsBooksForBooksThatAreNotForSale() {
        client.get().uri("/api/books/{id}/similar", duneMessiah)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].title").isEqualTo("Children of Dune");
    }

    @Test
    void limitsTheNumberOfRecommendations() {
        client.get().uri("/api/books/{id}/similar?limit=1", dune)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].title").isEqualTo("Children of Dune");
    }

    @Test
    void reflectsBookUpdates() {
        // Rewrite the cookbook into a desert-planet novel; its embedding should follow.
        AuthorId author = createAuthor("Anonymous");
        client.put().uri("/api/books/{id}", cookbook)
                .body(new BookRequest("Sands of Arrakis", List.of(author), Set.of(), "9780743246262", 2020,
                        "The spice must flow: a new story of the desert planet Arrakis and the Atreides."))
                .exchange()
                .expectStatus().isOk();

        await().untilAsserted(() -> client.get().uri("/api/books/{id}/similar", dune)
                .exchange()
                .expectBody()
                .jsonPath("$[*].title").value(titles -> assertThat(titles).asInstanceOf(LIST)
                        .containsExactlyInAnyOrder("Children of Dune", "Sands of Arrakis")));
    }

    @Test
    void stopsRecommendingDeletedBooks() {
        client.delete().uri("/api/books/{id}", childrenOfDune).exchange().expectStatus().isNoContent();

        await().untilAsserted(() -> client.get().uri("/api/books/{id}/similar", dune)
                .exchange()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].title").isEqualTo("The Joy of Cooking"));
        await().untilAsserted(() -> client.get().uri("/api/books/{id}/similar", childrenOfDune)
                .exchange()
                .expectStatus().isNotFound());
    }

    @Test
    void returns404ForUnknownBook() {
        client.get().uri("/api/books/{id}/similar", 999_999)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.detail").isEqualTo(
                        "No recommendations for book 999999 yet (unknown book, or it was added moments ago and "
                                + "isn't indexed yet)");
    }

    @Test
    void returns400ForInvalidLimit() {
        client.get().uri("/api/books/{id}/similar?limit=0", dune).exchange().expectStatus().isBadRequest();
        client.get().uri("/api/books/{id}/similar?limit=21", dune).exchange().expectStatus().isBadRequest();
    }

    private AuthorId createAuthor(String name) {
        return client.post().uri("/api/authors")
                .body(new AuthorRequest(name, Set.of()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthorResponse.class).returnResult().getResponseBody().id();
    }

    private BookId createBook(String title, AuthorId author, String isbn, String description) {
        return client.post().uri("/api/books")
                .body(new BookRequest(title, List.of(author), Set.of(), isbn, 2000, description))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookResponse.class).returnResult().getResponseBody().id();
    }

    private void setPrice(BookId book, String amount) {
        client.put().uri("/api/books/{id}/price", book)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": " + amount + "}")
                .exchange()
                .expectStatus().isOk();
    }

    private void rate(RestTestClient customer, BookId book, int stars) {
        customer.put().uri("/api/books/{id}/rating", book)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"stars\": " + stars + "}")
                .exchange()
                .expectStatus().isOk();
    }

    private RestTestClient signUp() {
        String username = "reader-" + UUID.randomUUID().toString().substring(0, 8);
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .build()
                .post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Rea", "lastName": "Reader", "password": "%s"}
                        """.formatted(username, PASSWORD))
                .exchange().expectStatus().isCreated();
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(username, PASSWORD))
                .build();
    }
}
