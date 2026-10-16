package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Anyone can sign up as a customer and then sign in with HTTP Basic, like staff, but without staff rights.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CustomerSignUpIntegrationTest {

    static final String PASSWORD = "customer-password";

    @LocalServerPort
    int port;

    RestTestClient anonymous;
    String username;

    @BeforeEach
    void setUp() {
        anonymous = client(null, null);
        username = "reader-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void anyoneCanSignUpAndThenSignIn() {
        register(username, "Rita", "Reader", PASSWORD)
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectBody()
                .jsonPath("$.username").isEqualTo(username)
                .jsonPath("$.firstName").isEqualTo("Rita")
                .jsonPath("$.role").isEqualTo("CUSTOMER")
                .jsonPath("$.password").doesNotExist();

        client(username, PASSWORD).get().uri("/api/accounts/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.role").isEqualTo("CUSTOMER");
    }

    @Test
    void cantSignUpAsStaff() {
        anonymous.post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Eve", "lastName": "Sneaky",
                         "password": "%s", "role": "ADMIN"}
                        """.formatted(username, PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.role").isEqualTo("CUSTOMER");
    }

    @Test
    void appliesTheAccountRules() {
        register(username, "Rita", "Reader", "short").expectStatus().isBadRequest();
        register("no spaces", "Rita", "Reader", PASSWORD).expectStatus().isBadRequest();
        register(username, " ", "Reader", PASSWORD).expectStatus().isBadRequest();

        register(username, "Rita", "Reader", PASSWORD).expectStatus().isCreated();
        register(username.toUpperCase(), "Rita", "Reader", PASSWORD).expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void customersBrowseAndManageOnlyTheirOwnAccount() {
        register(username, "Rita", "Reader", PASSWORD).expectStatus().isCreated();
        RestTestClient customer = client(username, PASSWORD);

        customer.get().uri("/api/books").exchange().expectStatus().isOk();
        customer.put().uri("/api/accounts/me").contentType(MediaType.APPLICATION_JSON)
                .body("{\"firstName\": \"Rita\", \"lastName\": \"Booklover\"}")
                .exchange().expectStatus().isOk();
        customer.put().uri("/api/accounts/me/password").contentType(MediaType.APPLICATION_JSON)
                .body("{\"currentPassword\": \"%s\", \"newPassword\": \"a-new-long-password\"}".formatted(PASSWORD))
                .exchange().expectStatus().isNoContent();

        RestTestClient signedIn = client(username, "a-new-long-password");
        signedIn.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Not Allowed\"}").exchange().expectStatus().isForbidden();
        signedIn.get().uri("/api/inventory").exchange().expectStatus().isForbidden();
        signedIn.get().uri("/api/audit").exchange().expectStatus().isForbidden();
        signedIn.get().uri("/api/accounts").exchange().expectStatus().isForbidden();
        signedIn.put().uri("/api/books/1/price").contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": 1}").exchange().expectStatus().isForbidden();
    }

    private RestTestClient.ResponseSpec register(String name, String firstName, String lastName, String password) {
        return anonymous.post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "%s", "lastName": "%s", "password": "%s"}
                        """.formatted(name, firstName, lastName, password))
                .exchange();
    }

    private RestTestClient client(String name, String password) {
        var builder = RestTestClient.bindToServer().baseUrl("http://localhost:" + port);
        if (name != null) {
            builder.defaultHeaders(headers -> headers.setBasicAuth(name, password));
        }
        return builder.build();
    }
}
