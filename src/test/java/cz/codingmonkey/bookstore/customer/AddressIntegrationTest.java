package cz.codingmonkey.bookstore.customer;

import static cz.codingmonkey.bookstore.customer.TestAddresses.INVOICE;
import static cz.codingmonkey.bookstore.customer.TestAddresses.SHIPPING;
import static cz.codingmonkey.bookstore.customer.TestAddresses.json;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * A customer's saved addresses over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class AddressIntegrationTest {

    private static final String PASSWORD = "buyer-password-1";

    @LocalServerPort
    int port;

    String username;
    RestTestClient customer;

    @BeforeEach
    void setUp() {
        username = "addr-" + UUID.randomUUID().toString().substring(0, 8);
        customer = signUp(username);
    }

    @Test
    void newCustomerHasNoAddresses() {
        customer.get().uri("/api/me/addresses").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.shipping").isEmpty()
                .jsonPath("$.invoice").isEmpty();
    }

    @Test
    void putThenGet() {
        put("shipping", json(SHIPPING)).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Bea Novak")
                .jsonPath("$.city").isEqualTo("Brno");
        customer.get().uri("/api/me/addresses").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.shipping.street").isEqualTo("Main 1")
                .jsonPath("$.invoice").isEmpty();
    }

    @Test
    void putReplaces() {
        put("invoice", json(INVOICE)).expectStatus().isOk();
        put("invoice", json(SHIPPING)).expectStatus().isOk();
        customer.get().uri("/api/me/addresses").exchange()
                .expectBody().jsonPath("$.invoice.name").isEqualTo("Bea Novak");
    }

    @Test
    void deleteIsIdempotent() {
        put("shipping", json(SHIPPING)).expectStatus().isOk();
        customer.delete().uri("/api/me/addresses/shipping").exchange().expectStatus().isNoContent();
        customer.delete().uri("/api/me/addresses/shipping").exchange().expectStatus().isNoContent();
        customer.delete().uri("/api/me/addresses/invoice").exchange().expectStatus().isNoContent();
        customer.get().uri("/api/me/addresses").exchange()
                .expectBody()
                .jsonPath("$.shipping").isEmpty()
                .jsonPath("$.invoice").isEmpty();
    }

    @Test
    void invalidBodyIs400() {
        for (String body : new String[] {
                json(new Address("Bea", " ", "Brno", "602 00", "CZ")),
                """
                {"name":"Bea","street":"Main 1","postalCode":"602 00","country":"CZ"}""",
                json(new Address("Bea", "Main 1", "Brno", "602 00", "CZE"))}) {
            put("shipping", body).expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith("application/problem+json");
        }
        customer.get().uri("/api/me/addresses").exchange()
                .expectBody().jsonPath("$.shipping").isEmpty();
    }

    @Test
    void countryIsStoredUpperCase() {
        put("shipping", json(new Address("Bea", "Main 1", "Brno", "602 00", "cz"))).expectStatus().isOk()
                .expectBody().jsonPath("$.country").isEqualTo("CZ");
        customer.get().uri("/api/me/addresses").exchange()
                .expectBody().jsonPath("$.shipping.country").isEqualTo("CZ");
    }

    @Test
    void customersAreIsolated() {
        put("shipping", json(SHIPPING)).expectStatus().isOk();
        signUp("addr-other-" + UUID.randomUUID().toString().substring(0, 8)).get().uri("/api/me/addresses")
                .exchange().expectBody()
                .jsonPath("$.shipping").isEmpty()
                .jsonPath("$.invoice").isEmpty();
    }

    @Test
    void usernameCaseDoesNotMatter() {
        put("shipping", json(SHIPPING)).expectStatus().isOk();
        RestTestClient upper = RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(h -> h.setBasicAuth(username.toUpperCase(), PASSWORD)).build();
        upper.get().uri("/api/me/addresses").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.shipping.city").isEqualTo("Brno");
    }

    private RestTestClient.ResponseSpec put(String kind, String body) {
        return customer.put().uri("/api/me/addresses/" + kind).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange();
    }

    private RestTestClient signUp(String name) {
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Bea", "lastName": "Buyer", "password": "%s"}
                        """.formatted(name, PASSWORD))
                .exchange().expectStatus().isCreated();
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(name, PASSWORD))
                .build();
    }
}
