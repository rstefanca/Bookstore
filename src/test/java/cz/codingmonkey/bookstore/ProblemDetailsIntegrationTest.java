package cz.codingmonkey.bookstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every error response is an RFC 9457 problem detail ({@code application/problem+json}), whether it comes from a
 * module's exception, request validation, Spring MVC itself, the security filters or an unexpected failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, ProblemDetailsIntegrationTest.FailingController.class})
class ProblemDetailsIntegrationTest {

    /** Fails unexpectedly; mapped under a path staff may call, since unlisted paths are denied. */
    @RestController
    static class FailingController {

        @GetMapping("/api/genres/test/failure")
        String fail() {
            throw new IllegalStateException("internal secret");
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    RestTestClient admin;

    RestTestClient anonymous;

    @BeforeEach
    void setUp() {
        anonymous = client().build();
    }

    @Test
    void moduleExceptionsCarryTheirMessage() {
        problem(anonymous.get().uri("/api/books/999999").exchange(), HttpStatus.NOT_FOUND, "/api/books/999999")
                .jsonPath("$.detail").isEqualTo("Book 999999 not found");

        problem(admin.post().uri("/api/genres").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"science fiction\"}").exchange(), HttpStatus.CONFLICT, "/api/genres")
                .jsonPath("$.detail").isEqualTo("Genre \"science fiction\" already exists");
    }

    @Test
    void responseStatusExceptionsCarryTheirReason() {
        problem(admin.get().uri("/api/audit?subjectType=BOOK").exchange(), HttpStatus.BAD_REQUEST, "/api/audit")
                .jsonPath("$.detail").isEqualTo("Filter by both subjectType and subjectId");
    }

    @Test
    void invalidRequestBodiesListTheFieldErrors() {
        String book = "{\"title\": \" \", \"authorIds\": [], \"isbn\": \"9780000000001\"}";

        problem(admin.post().uri("/api/books").contentType(MediaType.APPLICATION_JSON).body(book).exchange(),
                HttpStatus.BAD_REQUEST, "/api/books")
                .jsonPath("$.errors[?(@.field == 'title')].message").isEqualTo("must not be blank")
                .jsonPath("$.errors[?(@.field == 'authorIds')].message").isEqualTo("must not be empty");
    }

    @Test
    void invalidParametersListTheParameterErrors() {
        problem(anonymous.get().uri("/api/books/1/similar?limit=0").exchange(), HttpStatus.BAD_REQUEST,
                "/api/books/1/similar")
                .jsonPath("$.errors[0].field").isEqualTo("limit")
                .jsonPath("$.errors[0].message").isEqualTo("must be greater than or equal to 1");

        problem(anonymous.get().uri("/api/books/abc").exchange(), HttpStatus.BAD_REQUEST, "/api/books/abc");
    }

    @Test
    void springMvcErrorsAreProblemsToo() {
        problem(admin.delete().uri("/api/genres").exchange(), HttpStatus.METHOD_NOT_ALLOWED, "/api/genres");
        problem(admin.put().uri("/api/genres/1").contentType(MediaType.APPLICATION_JSON).body("{not json")
                .exchange(), HttpStatus.BAD_REQUEST, "/api/genres/1");
    }

    @Test
    void securityErrorsAreProblemsWithoutDetails() {
        var unauthorized = anonymous.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Anonymous Author\"}").exchange();
        unauthorized.expectHeader().valueMatches("WWW-Authenticate", "Basic realm=.*");
        problem(unauthorized, HttpStatus.UNAUTHORIZED, "/api/authors").jsonPath("$.detail").doesNotExist();

        problem(client().defaultHeaders(headers -> headers.setBasicAuth("nobody", "wrong-password")).build()
                .get().uri("/api/books").exchange(), HttpStatus.UNAUTHORIZED, "/api/books");

        String username = "customer-" + UUID.randomUUID().toString().substring(0, 8);
        anonymous.post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Cy", "lastName": "Customer",
                         "password": "customer-password"}""".formatted(username))
                .exchange().expectStatus().isCreated();
        RestTestClient customer = client()
                .defaultHeaders(headers -> headers.setBasicAuth(username, "customer-password")).build();
        problem(customer.get().uri("/api/inventory").exchange(), HttpStatus.FORBIDDEN, "/api/inventory")
                .jsonPath("$.detail").doesNotExist();
    }

    @Test
    void unexpectedFailuresDontExposeTheirMessage() {
        problem(admin.get().uri("/api/genres/test/failure").exchange(), HttpStatus.INTERNAL_SERVER_ERROR,
                "/api/genres/test/failure")
                .jsonPath("$.detail").doesNotExist()
                .consumeWith(response -> assertThat(new String(response.getResponseBodyContent()))
                        .doesNotContain("internal secret"));
    }

    private RestTestClient.BodyContentSpec problem(RestTestClient.ResponseSpec response, HttpStatus status,
            String instance) {
        return response
                .expectStatus().isEqualTo(status)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(status.value())
                .jsonPath("$.title").isEqualTo(status.getReasonPhrase())
                .jsonPath("$.instance").isEqualTo(instance)
                // "about:blank", the default type, is left out (RFC 9457 treats a missing type as that).
                .jsonPath("$.type").doesNotExist();
    }

    private RestTestClient.Builder<?> client() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port);
    }
}
