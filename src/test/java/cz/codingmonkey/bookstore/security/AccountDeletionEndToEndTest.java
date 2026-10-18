package cz.codingmonkey.bookstore.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.customer.TestAddresses;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Deleting accounts over HTTP with every module running: an account nobody keeps records of is deleted, one with
 * orders, ratings or audit entries is anonymized and its username is free again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class AccountDeletionEndToEndTest {

    static final String PASSWORD = "a-long-password";
    static final String DELETED_USER = "(deleted user)";

    @LocalServerPort
    int port;

    /** Signed in as the test admin. */
    @Autowired
    RestTestClient admin;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void customerWithOnlyCartAndAddressIsHardDeleted() {
        String name = username("cart");
        long id = register(name);
        RestTestClient customer = client(name);
        long book = book("9.99", 5);
        saveAddresses(customer);
        setQuantity(customer, book, 1);

        delete(id);

        assertThat(count("account WHERE id = ?", id)).isZero();
        assertThat(count("cart WHERE customer_id = ?", id)).isZero();
        assertThat(count("customer_address WHERE customer_id = ?", id)).isZero();
        customer.get().uri("/api/accounts/me").exchange().expectStatus().isUnauthorized();

        long again = register(name);
        assertThat(again).isNotEqualTo(id);
        RestTestClient fresh = client(name);
        fresh.get().uri("/api/cart").exchange().expectBody().jsonPath("$.items").isEmpty();
        fresh.get().uri("/api/me/addresses").exchange().expectBody()
                .jsonPath("$.shipping").isEmpty().jsonPath("$.invoice").isEmpty();
        fresh.get().uri("/api/books/{id}/rating", book).exchange().expectStatus().isNotFound();
    }

    @Test
    void customerWithOrderAndRatingIsSoftDeleted() {
        String name = username("buyer");
        long id = register(name);
        RestTestClient customer = client(name);
        long book = book("9.99", 5);
        saveAddresses(customer);
        rate(customer, book, 5);
        setQuantity(customer, book, 1);
        long order = checkout(customer);
        awaitStatus(order, "CONFIRMED");
        setQuantity(customer, book, 1); // a cart again, to be removed
        assertThat(count("cart WHERE customer_id = ?", id)).isOne();

        delete(id);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM account WHERE id = ?", id);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("username")).isEqualTo("~deleted-" + id);
        customer.get().uri("/api/accounts/me").exchange().expectStatus().isUnauthorized();
        assertThat(listedIds()).doesNotContain(id);

        // The old order: the name is gone but the id stays. The staff list shows no addresses, so those are checked
        // in the table: shipping is scrubbed, the invoice address stays.
        admin.get().uri("/api/orders/all?size=100&sort=id,desc").exchange().expectBody()
                .jsonPath("$.content[?(@.id == %d)].customer".formatted(order)).value(v -> assertThat(v)
                        .isEqualTo(List.of(DELETED_USER)))
                .jsonPath("$.content[?(@.id == %d)].customerId".formatted(order)).value(v -> assertThat(v)
                        .isEqualTo(List.of((int) id)));
        assertThat(count("customer_order WHERE customer_id = ? AND shipping_name IS NULL "
                + "AND shipping_street IS NULL AND shipping_city IS NULL AND shipping_postal_code IS NULL "
                + "AND shipping_country IS NULL AND invoice_city = ?", id, TestAddresses.INVOICE.city())).isOne();
        assertThat(count("cart WHERE customer_id = ?", id)).isZero();
        assertThat(count("customer_address WHERE customer_id = ?", id)).isZero();
        admin.get().uri("/api/books/{id}", book).exchange().expectBody()
                .jsonPath("$.rating.count").isEqualTo(1)
                .jsonPath("$.rating.average").isEqualTo(5.0);

        // The username is free and starts clean.
        long again = register(name);
        assertThat(again).isNotEqualTo(id);
        RestTestClient fresh = client(name);
        fresh.get().uri("/api/orders").exchange().expectBody().jsonPath("$.content").isEmpty();
        fresh.get().uri("/api/cart").exchange().expectBody().jsonPath("$.items").isEmpty();
        fresh.get().uri("/api/me/addresses").exchange().expectBody()
                .jsonPath("$.shipping").isEmpty().jsonPath("$.invoice").isEmpty();
        fresh.get().uri("/api/books/{id}/rating", book).exchange().expectStatus().isNotFound();
    }

    @Test
    void staffWithoutAuditEntriesIsHardDeleted() {
        String name = username("idle");
        long id = createAccount(name, "STAFF");

        delete(id);

        assertThat(count("account WHERE id = ?", id)).isZero();
    }

    @Test
    void staffWithAuditEntriesIsSoftDeletedAndReuseStartsClean() {
        String name = username("editor");
        long id = createAccount(name, "STAFF");
        RestTestClient staff = client(name);
        long author = createAuthor(staff);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(count("audit_entry WHERE actor_id = ?", id)).isPositive());

        delete(id);

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM account WHERE id = ?", Object.class, id)).isNotNull();
        staff.get().uri("/api/books").exchange().expectStatus().isUnauthorized();
        admin.get().uri("/api/audit?subjectType=AUTHOR&subjectId={id}", author).exchange().expectBody()
                .jsonPath("$.content[0].actor").isEqualTo(DELETED_USER)
                .jsonPath("$.content[0].actorId").isEqualTo((int) id);

        long again = createAccount(name, "STAFF");
        assertThat(again).isNotEqualTo(id);
        assertThat(count("audit_entry WHERE actor_id = ?", again)).isZero();
        admin.get().uri("/api/audit?size=100").exchange().expectBody()
                .jsonPath("$.content[?(@.actorId == %d)]".formatted(again)).value(v -> assertThat(v)
                        .isEqualTo(List.of()));
    }

    @Test
    void softDeletedAdminNoLongerCountsAsAdmin() {
        String name = username("boss");
        long id = createAccount(name, "ADMIN");
        createAuthor(client(name));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(count("audit_entry WHERE actor_id = ?", id)).isPositive());
        int liveAdmins = count("account WHERE role = 'ADMIN' AND deleted_at IS NULL");

        delete(id);

        assertThat(count("account WHERE id = ? AND deleted_at IS NOT NULL", id)).isOne();
        assertThat(count("account WHERE role = 'ADMIN' AND deleted_at IS NULL")).isEqualTo(liveAdmins - 1);
        assertThat(listedIds()).doesNotContain(id);
        // Another admin can still be deleted: the soft-deleted one is not what keeps the rule satisfied.
        long other = createAccount(username("spare"), "ADMIN");
        delete(other);
    }

    @Test
    void oldCredentialsGive401AfterDeletion() {
        String name = username("gone");
        long id = register(name);
        RestTestClient customer = client(name);
        customer.get().uri("/api/accounts/me").exchange().expectStatus().isOk();

        delete(id);

        customer.get().uri("/api/accounts/me").exchange().expectStatus().isUnauthorized();
        customer.get().uri("/api/books").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void ownerLookupsAreIndexed() {
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE indexname IN "
                + "('audit_entry_actor_idx', 'book_rating_customer_idx')", String.class))
                .containsExactlyInAnyOrder("audit_entry_actor_idx", "book_rating_customer_idx");
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private void delete(long id) {
        admin.delete().uri("/api/accounts/{id}", id).exchange().expectStatus().isNoContent();
    }

    private int count(String fromAndWhere, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromAndWhere, Integer.class, args);
    }

    private List<Long> listedIds() {
        List<Long> ids = new ArrayList<>();
        for (int page = 0; page < 1000; page++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = admin.get().uri("/api/accounts?size=100&page={p}", page).exchange()
                    .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
            content.forEach(account -> ids.add(((Number) account.get("id")).longValue()));
            if (content.size() < 100) {
                break;
            }
        }
        return ids;
    }

    private long register(String name) {
        return idOf(RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Bea", "lastName": "Buyer", "password": "%s"}
                        """.formatted(name, PASSWORD))
                .exchange());
    }

    private long createAccount(String name, String role) {
        return idOf(admin.post().uri("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Jane", "lastName": "Doe", "password": "%s", "role": "%s"}
                        """.formatted(name, PASSWORD, role))
                .exchange());
    }

    private RestTestClient client(String name) {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(name, PASSWORD))
                .build();
    }

    private static long idOf(RestTestClient.ResponseSpec created) {
        return ((Number) created.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody()
                .get("id")).longValue();
    }

    private long createAuthor(RestTestClient as) {
        return idOf(as.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Deletion Author\"}").exchange());
    }

    private long book(String price, int stock) {
        long author = createAuthor(admin);
        String isbn = "del-" + UUID.randomUUID().toString().substring(0, 12);
        long id = idOf(admin.post().uri("/api/books").contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\": \"Book %s\", \"authorIds\": [%d], \"isbn\": \"%s\"}".formatted(isbn, author, isbn))
                .exchange());
        admin.put().uri("/api/books/{id}/price", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": %s}".formatted(price)).exchange().expectStatus().isOk();
        admin.post().uri("/api/inventory/{id}/restock", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": %d}".formatted(stock)).exchange().expectStatus().isOk();
        return id;
    }

    private static void saveAddresses(RestTestClient client) {
        client.put().uri("/api/me/addresses/shipping").contentType(MediaType.APPLICATION_JSON)
                .body(TestAddresses.json(TestAddresses.SHIPPING)).exchange().expectStatus().isOk();
        client.put().uri("/api/me/addresses/invoice").contentType(MediaType.APPLICATION_JSON)
                .body(TestAddresses.json(TestAddresses.INVOICE)).exchange().expectStatus().isOk();
    }

    private static void setQuantity(RestTestClient client, long book, int quantity) {
        client.put().uri("/api/cart/items/{id}", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": %d}".formatted(quantity)).exchange().expectStatus().isOk();
    }

    private static void rate(RestTestClient client, long book, int stars) {
        client.put().uri("/api/books/{id}/rating", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"stars\": %d}".formatted(stars)).exchange().expectStatus().isOk();
    }

    private static long checkout(RestTestClient client) {
        return ((Number) client.post().uri("/api/cart/checkout").exchange().expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody().get("id")).longValue();
    }

    private void awaitStatus(long order, String status) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, order))
                        .isEqualTo(status));
    }

    private static String username(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
