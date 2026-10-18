package cz.codingmonkey.bookstore.ordering;

import static cz.codingmonkey.bookstore.customer.TestAddresses.INVOICE;
import static cz.codingmonkey.bookstore.customer.TestAddresses.SHIPPING;
import static cz.codingmonkey.bookstore.customer.TestAddresses.json;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Over HTTP, all modules running: checkout copies the shipping and invoice address (sent or saved) into the order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class CheckoutAddressesIntegrationTest {

    @LocalServerPort
    int port;

    /** Signed in as the test admin (staff rights). */
    @Autowired
    RestTestClient staff;

    @Autowired
    JdbcTemplate jdbc;

    String buyer;
    RestTestClient customer;
    long book;

    @BeforeEach
    void setUp() {
        buyer = "addr-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        customer = signUp(buyer);
        String isbn = "addr-" + UUID.randomUUID().toString().substring(0, 12);
        long author = id(staff.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Address Author\"}").exchange());
        book = id(staff.post().uri("/api/books").contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\": \"Book %s\", \"authorIds\": [%d], \"isbn\": \"%s\"}".formatted(isbn, author, isbn))
                .exchange());
        staff.put().uri("/api/books/{id}/price", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": 9.99}").exchange().expectStatus().isOk();
        staff.post().uri("/api/inventory/{id}/restock", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 5}").exchange().expectStatus().isOk();
        customer.put().uri("/api/cart/items/{id}", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 1}").exchange().expectStatus().isOk();
    }

    @Test
    void savedAddressesAreCopiedToTheOrder() {
        save("shipping", SHIPPING);
        save("invoice", INVOICE);

        checkout(null).expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.shipping.city").isEqualTo("Brno")
                .jsonPath("$.invoice.city").isEqualTo("Praha");
    }

    @Test
    void editingSavedAddressesDoesNotChangeTheOrder() {
        save("shipping", SHIPPING);
        save("invoice", INVOICE);
        long order = orderId(checkout(null));

        customer.put().uri("/api/me/addresses/shipping").contentType(MediaType.APPLICATION_JSON)
                .body(json(INVOICE)).exchange().expectStatus().isOk();

        customer.get().uri("/api/orders/{id}", order).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.shipping.city").isEqualTo("Brno");
    }

    @Test
    void bodyMayHaveOnlyOneAddress() {
        save("shipping", SHIPPING);

        checkout("{\"invoice\": " + json(INVOICE) + "}").expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.shipping.city").isEqualTo("Brno")
                .jsonPath("$.invoice.city").isEqualTo("Praha");
        customer.get().uri("/api/me/addresses").exchange()
                .expectBody().jsonPath("$.invoice").isEmpty();

        save("invoice", INVOICE);
        customer.put().uri("/api/cart/items/{id}", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 1}").exchange().expectStatus().isOk();
        checkout("{}").expectStatus().isAccepted();
        customer.put().uri("/api/cart/items/{id}", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 1}").exchange().expectStatus().isOk();
        checkout(null).expectStatus().isAccepted();
    }

    @Test
    void missingAddressIs400WithMessageAndKeepsCartAndStock() {
        checkout(null).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail")
                .isEqualTo("Shipping and invoice addresses are required: send them or save them");

        customer.get().uri("/api/cart").exchange()
                .expectBody().jsonPath("$.items.length()").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_item WHERE book_id = ?", Integer.class, book))
                .isEqualTo(5);
    }

    @Test
    void invalidAddressInBodyIs400() {
        checkout("{\"shipping\": {\"name\": \"\"}, \"invoice\": " + json(INVOICE) + "}")
                .expectStatus().isBadRequest();
    }

    @Test
    void orderAddressesVisibleToOwnerNotInStaffList() {
        save("shipping", SHIPPING);
        save("invoice", INVOICE);
        long order = orderId(checkout(null));

        customer.get().uri("/api/orders").exchange()
                .expectBody().jsonPath("$.content[0].shipping.city").isEqualTo("Brno");
        staff.get().uri("/api/orders/all?size=100").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[?(@.id == %d)].customer".formatted(order)).exists()
                .jsonPath("$.content[?(@.id == %d)].shipping".formatted(order)).value(v -> assertThat(v).asList()
                        .containsOnlyNulls())
                .jsonPath("$.content[?(@.id == %d)].invoice".formatted(order)).value(v -> assertThat(v).asList()
                        .containsOnlyNulls());
    }

    @Test
    void oldOrdersHaveNullAddresses() {
        long order = jdbc.queryForObject("""
                INSERT INTO customer_order (customer_id, status, total, currency, created_at, version)
                SELECT id, 'CONFIRMED', 10.00, 'EUR', now(), 0 FROM account WHERE username = ? RETURNING id""",
                Long.class, buyer);

        customer.get().uri("/api/orders/{id}", order).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.shipping").isEmpty()
                .jsonPath("$.invoice").isEmpty();
    }

    private void save(String kind, cz.codingmonkey.bookstore.customer.Address address) {
        customer.put().uri("/api/me/addresses/" + kind).contentType(MediaType.APPLICATION_JSON)
                .body(json(address)).exchange().expectStatus().isOk();
    }

    private RestTestClient.ResponseSpec checkout(String body) {
        var request = customer.post().uri("/api/cart/checkout");
        return (body == null ? request : request.contentType(MediaType.APPLICATION_JSON).body(body)).exchange();
    }

    private static long orderId(RestTestClient.ResponseSpec checkedOut) {
        return ((Number) checkedOut.expectStatus().isEqualTo(HttpStatus.ACCEPTED).expectBody(Map.class)
                .returnResult().getResponseBody().get("id")).longValue();
    }

    private RestTestClient signUp(String username) {
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Bea", "lastName": "Buyer", "password": "buyer-password-1"}
                        """.formatted(username))
                .exchange().expectStatus().isCreated();
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(username, "buyer-password-1"))
                .build();
    }

    private static long id(RestTestClient.ResponseSpec created) {
        return ((Number) created.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody()
                .get("id")).longValue();
    }
}
