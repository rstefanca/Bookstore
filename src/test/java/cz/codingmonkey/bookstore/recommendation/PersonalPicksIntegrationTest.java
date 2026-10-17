package cz.codingmonkey.bookstore.recommendation;

import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
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
 * Personal picks over HTTP, all modules running. The ranking itself is covered by
 * {@link PersonalPicksServiceIntegrationTest}; this checks the endpoint, its limits and who may call it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class PersonalPicksIntegrationTest {

    private static final String PASSWORD = "buyer-password-1";

    @LocalServerPort
    int port;

    /** Signed in as the test admin (staff rights). */
    @Autowired
    RestTestClient admin;

    @Autowired
    JdbcTemplate jdbc;

    RestTestClient customer;
    long dune;

    @BeforeEach
    void setUp() {
        customer = signUp(username("picker"));
        dune = createBook("Dune");
    }

    @Test
    void customerGetsPicksFromTheirRatings() {
        put(customer, dune, 5).expectStatus().isOk();

        customer.get().uri("/api/recommendations").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.personal").isEqualTo(true)
                .jsonPath("$.books").isArray()
                .jsonPath("$.books[?(@.bookId == %d)]".formatted(dune)).isEmpty();
    }

    @Test
    void picksCarryTitleAndAuthors() {
        long sequel = createBook("Children of Dune");
        put(customer, dune, 5).expectStatus().isOk();

        customer.get().uri("/api/recommendations?limit=20").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.books[?(@.bookId == %d)].title".formatted(sequel)).isEqualTo("Children of Dune")
                .jsonPath("$.books[?(@.bookId == %d)].authors[0]".formatted(sequel)).isEqualTo("Rated Author");
    }

    @Test
    void sameCustomerInAnyLetterCase() {
        String username = username("bea");
        RestTestClient lower = signUp(username);
        RestTestClient upper = client(username.toUpperCase());

        put(upper, dune, 5).expectStatus().isOk();

        // Liked books are found only if the name matches whatever the case: otherwise this is the fallback.
        lower.get().uri("/api/recommendations").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.personal").isEqualTo(true)
                .jsonPath("$.books[?(@.bookId == %d)]".formatted(dune)).isEmpty();
    }

    @Test
    void rejectsLimitOutOfRange() {
        for (int limit : new int[] {0, 21}) {
            customer.get().uri("/api/recommendations?limit={limit}", limit).exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Test
    void anonymousIs401() {
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/recommendations").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void staffIs403() {
        String username = username("clerk");
        admin.post().uri("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Sam", "lastName": "Staff", "password": "%s", "role": "STAFF"}
                        """.formatted(username, PASSWORD))
                .exchange().expectStatus().isCreated();

        client(username).get().uri("/api/recommendations").exchange().expectStatus().isForbidden();
        admin.get().uri("/api/recommendations").exchange().expectStatus().isForbidden();
    }

    private RestTestClient.ResponseSpec put(RestTestClient client, long bookId, int stars) {
        return client.put().uri("/api/books/{id}/rating", bookId).contentType(MediaType.APPLICATION_JSON)
                .body("{\"stars\": %d}".formatted(stars)).exchange();
    }

    /** Creates a priced book and waits until its embedding exists, as picks only use books with one. */
    private long createBook(String title) {
        String isbn = "pck-" + UUID.randomUUID().toString().substring(0, 12);
        var author = admin.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Rated Author\"}").exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        long authorId = ((Number) author.get("id")).longValue();
        var created = admin.post().uri("/api/books").contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\": \"%s\", \"authorIds\": [%d], \"isbn\": \"%s\"}".formatted(title, authorId, isbn))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        long id = ((Number) created.get("id")).longValue();
        admin.put().uri("/api/books/{id}/price", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": 12.90}").exchange().expectStatus().isOk();
        await().until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM book_embedding WHERE book_id = ? AND NOT deleted", Integer.class, id) == 1);
        return id;
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
