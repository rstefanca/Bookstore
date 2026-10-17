package cz.codingmonkey.bookstore.security;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestAccounts;
import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Who may call what, checked over HTTP with anonymous, staff and admin clients.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SecurityIntegrationTest {

    static final String STAFF_PASSWORD = "staff-password-123";

    @LocalServerPort
    int port;

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcTemplate jdbc;

    RestTestClient anonymous;
    RestTestClient staff;
    RestTestClient admin;
    String staffUsername;

    @BeforeEach
    void setUp() {
        staffUsername = "staff-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(staffUsername, "Sam", "Staff", STAFF_PASSWORD, Role.STAFF);
        anonymous = client(null, null);
        staff = client(staffUsername, STAFF_PASSWORD);
        admin = client(TestAccounts.ADMIN_USERNAME, TestAccounts.ADMIN_PASSWORD);
    }

    @Test
    void anyoneCanBrowse() {
        for (String path : new String[] {"/api/books", "/api/authors", "/api/genres", "/api/prices"}) {
            anonymous.get().uri(path).exchange().expectStatus().isOk();
        }
        anonymous.get().uri("/api/books/999999").exchange().expectStatus().isNotFound();
        anonymous.get().uri("/api/books/999999/price").exchange().expectStatus().isNotFound();
        anonymous.get().uri("/api/books/999999/similar").exchange().expectStatus().isNotFound();
    }

    @Test
    void changesNeedASignedInStaffMember() {
        String author = "{\"name\": \"Anonymous Author\"}";

        anonymous.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON).body(author)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches("WWW-Authenticate", "Basic realm=.*");
        staff.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON).body(author)
                .exchange().expectStatus().isCreated();
    }

    @Test
    void staffAreNeededForInventoryAndPriceHistory() {
        anonymous.get().uri("/api/inventory").exchange().expectStatus().isUnauthorized();
        anonymous.get().uri("/api/books/1/price-history").exchange().expectStatus().isUnauthorized();
        anonymous.put().uri("/api/books/1/price").contentType(MediaType.APPLICATION_JSON).body("{\"amount\": 1}")
                .exchange().expectStatus().isUnauthorized();

        staff.get().uri("/api/inventory").exchange().expectStatus().isOk();
        staff.get().uri("/api/books/1/price-history").exchange().expectStatus().isOk();
    }

    @Test
    void onlyCustomersRateBooks() {
        String stars = "{\"stars\": 4}";
        anonymous.put().uri("/api/books/1/rating").contentType(MediaType.APPLICATION_JSON).body(stars)
                .exchange().expectStatus().isUnauthorized();
        anonymous.get().uri("/api/books/1/rating").exchange().expectStatus().isUnauthorized();
        staff.put().uri("/api/books/1/rating").contentType(MediaType.APPLICATION_JSON).body(stars)
                .exchange().expectStatus().isForbidden();
        admin.put().uri("/api/books/1/rating").contentType(MediaType.APPLICATION_JSON).body(stars)
                .exchange().expectStatus().isForbidden();

        String customer = "customer-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(customer, "Cora", "Customer", STAFF_PASSWORD, Role.CUSTOMER);
        client(customer, STAFF_PASSWORD).delete().uri("/api/books/1/rating")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void auditLogAndAccountsAreForAdminsOnly() {
        anonymous.get().uri("/api/audit").exchange().expectStatus().isUnauthorized();
        staff.get().uri("/api/audit").exchange().expectStatus().isForbidden();
        staff.get().uri("/api/accounts").exchange().expectStatus().isForbidden();

        admin.get().uri("/api/audit").exchange().expectStatus().isOk();
        admin.get().uri("/api/accounts").exchange().expectStatus().isOk();
    }

    @Test
    void adminsCanDoEverythingStaffCan() {
        admin.get().uri("/api/inventory").exchange().expectStatus().isOk();
        admin.post().uri("/api/genres").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Admin Genre " + UUID.randomUUID() + "\"}")
                .exchange().expectStatus().isCreated();
    }

    @Test
    void everyoneSignedInSeesTheirOwnAccount() {
        anonymous.get().uri("/api/accounts/me").exchange().expectStatus().isUnauthorized();
        staff.get().uri("/api/accounts/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo(staffUsername)
                .jsonPath("$.role").isEqualTo("STAFF");
    }

    @Test
    void rejectsWrongCredentials() {
        client(staffUsername, "wrong-password-1").get().uri("/api/accounts/me")
                .exchange().expectStatus().isUnauthorized();
        client("nobody-" + UUID.randomUUID(), STAFF_PASSWORD).get().uri("/api/accounts/me")
                .exchange().expectStatus().isUnauthorized();
        // Wrong credentials are rejected even where anonymous access would be allowed.
        client(staffUsername, "wrong-password-1").get().uri("/api/books")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void usernamesAreCaseInsensitive() {
        client(staffUsername.toUpperCase(), STAFF_PASSWORD).get().uri("/api/accounts/me")
                .exchange().expectStatus().isOk();
    }

    @Test
    void deniesEndpointsWithoutARule() {
        anonymous.get().uri("/api/not-a-thing").exchange().expectStatus().isUnauthorized();
        admin.get().uri("/api/not-a-thing").exchange().expectStatus().isForbidden();
        admin.get().uri("/not-a-thing").exchange().expectStatus().isForbidden();
    }

    @Test
    void actuatorBeyondHealthIsForAdminsOnly() {
        staff.get().uri("/actuator/health").exchange().expectStatus().isOk();
        staff.get().uri("/actuator/metrics").exchange().expectStatus().isForbidden();
        staff.get().uri("/actuator/prometheus").exchange().expectStatus().isForbidden();
        admin.get().uri("/actuator").exchange().expectStatus().isOk();
    }

    @Test
    void doesNotCreateSessions() {
        staff.get().uri("/api/accounts/me")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Set-Cookie");
    }

    @Test
    void storesPasswordsOnlyAsHashes() {
        String stored = jdbc.queryForObject("SELECT password_hash FROM account WHERE username = ?", String.class,
                staffUsername);

        assertThat(stored).startsWith("{bcrypt}$2").doesNotContain(STAFF_PASSWORD);
    }

    private RestTestClient client(String username, String password) {
        var builder = RestTestClient.bindToServer().baseUrl("http://localhost:" + port);
        if (username != null) {
            builder.defaultHeaders(headers -> headers.setBasicAuth(username, password));
        }
        return builder.build();
    }
}
