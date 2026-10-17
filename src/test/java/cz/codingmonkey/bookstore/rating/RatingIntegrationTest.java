package cz.codingmonkey.bookstore.rating;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
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
 * Customers rate books over HTTP, all modules running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class RatingIntegrationTest {

    private static final String PASSWORD = "buyer-password-1";

    @LocalServerPort
    int port;

    /** Signed in as the test admin (staff rights). */
    @Autowired
    RestTestClient staff;

    @Autowired
    JdbcTemplate jdbc;

    RestTestClient customer;
    long book;

    @BeforeEach
    void setUp() {
        customer = signUp(username("rater"));
        book = createBook();
    }

    @Test
    void customerRatesABook() {
        put(customer, book, "{\"stars\": 4}")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.bookId").isEqualTo(book)
                .jsonPath("$.stars").isEqualTo(4)
                .jsonPath("$.ratedAt").exists();

        customer.get().uri("/api/books/{id}/rating", book).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.stars").isEqualTo(4);
    }

    @Test
    void ratingAgainReplacesIt() {
        put(customer, book, "{\"stars\": 2}").expectStatus().isOk();
        put(customer, book, "{\"stars\": 5}").expectStatus().isOk();

        customer.get().uri("/api/books/{id}/rating", book).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.stars").isEqualTo(5);
        assertThat(ratingRows()).isEqualTo(1);
    }

    @Test
    void sameCustomerInAnyLetterCase() {
        String username = username("bea");
        RestTestClient lower = signUp(username);
        RestTestClient upper = client(username.toUpperCase());

        put(lower, book, "{\"stars\": 3}").expectStatus().isOk();
        put(upper, book, "{\"stars\": 5}").expectStatus().isOk();

        assertThat(ratingRows()).isEqualTo(1);
        lower.get().uri("/api/books/{id}/rating", book).exchange()
                .expectBody().jsonPath("$.stars").isEqualTo(5);
    }

    @Test
    void rejectsInvalidStars() {
        for (String body : List.of("{\"stars\": 0}", "{\"stars\": 6}", "{}", "{\"stars\": 4.5}")) {
            put(customer, book, body)
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
        assertThat(ratingRows()).isZero();
    }

    @Test
    void unknownBookIs404() {
        put(customer, 999999999L, "{\"stars\": 4}")
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.detail").isEqualTo("No book with id 999999999");
    }

    @Test
    void deletedBookIs404() {
        long deleted = createBook();
        staff.delete().uri("/api/books/{id}", deleted).exchange().expectStatus().isNoContent();

        put(customer, deleted, "{\"stars\": 4}").expectStatus().isNotFound();
    }

    @Test
    void notRatedYetIs404() {
        customer.get().uri("/api/books/{id}/rating", book).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.detail").isEqualTo("You haven't rated this book");
    }

    private int ratingRows() {
        return jdbc.queryForObject("SELECT count(*) FROM book_rating WHERE book_id = ?", Integer.class, book);
    }

    private static RestTestClient.ResponseSpec put(RestTestClient client, long bookId, String body) {
        return client.put().uri("/api/books/{id}/rating", bookId).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange();
    }

    private long createBook() {
        String isbn = "rat-" + UUID.randomUUID().toString().substring(0, 12);
        var author = staff.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Rated Author\"}").exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        long authorId = ((Number) author.get("id")).longValue();
        var created = staff.post().uri("/api/books").contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\": \"Book %s\", \"authorIds\": [%d], \"isbn\": \"%s\"}".formatted(isbn, authorId, isbn))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) created.get("id")).longValue();
    }

    private RestTestClient signUp(String username) {
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Bea", "lastName": "Buyer", "password": "%s"}
                        """.formatted(username, PASSWORD))
                .exchange().expectStatus().isCreated();
        return client(username);
    }

    private RestTestClient client(String username) {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(username, PASSWORD))
                .build();
    }

    private static String username(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
