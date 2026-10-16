package cz.codingmonkey.bookstore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
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
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * End to end: changes made by a signed-in staff member show up in the audit log with their username, including
 * follow-up changes other modules make in the background (the price removed after the book was deleted).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class AuditActorIntegrationTest {

    @LocalServerPort
    int port;

    /** Signed in as the test admin. */
    @Autowired
    RestTestClient admin;

    String staffUsername;
    RestTestClient staff;

    @BeforeEach
    void createStaffMember() {
        staffUsername = "auditor-" + UUID.randomUUID().toString().substring(0, 8);
        admin.post().uri("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Sam", "lastName": "Staff",
                         "password": "a-long-password", "role": "STAFF"}
                        """.formatted(staffUsername))
                .exchange().expectStatus().isCreated();
        staff = RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(staffUsername, "a-long-password"))
                .build();
    }

    @Test
    void recordsWhoMadeEachChange() {
        long author = id(post("/api/authors", "{\"name\": \"Audited Author\"}"));
        String isbn = "audit-" + UUID.randomUUID().toString().substring(0, 8);
        long book = id(post("/api/books", """
                {"title": "Audited Book", "authorIds": [%d], "isbn": "%s"}
                """.formatted(author, isbn)));
        staff.put().uri("/api/books/{id}/price", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": 10}").exchange().expectStatus().isOk();
        staff.post().uri("/api/inventory/{id}/restock", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 1}").exchange().expectStatus().isOk();
        staff.post().uri("/api/inventory/{id}/withdraw", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": 1}").exchange().expectStatus().isOk();
        staff.delete().uri("/api/books/{id}", book).exchange().expectStatus().isNoContent();

        // Audit entries are recorded asynchronously after each change commits.
        await().untilAsserted(() -> admin.get().uri("/api/audit?bookId={id}&size=100", book)
                .exchange()
                .expectBody()
                .jsonPath("$.content[*].action").value(actions -> assertThat(actions).asInstanceOf(LIST)
                        .containsExactlyInAnyOrder("BOOK_CREATED", "PRICE_CHANGED", "STOCK_ADJUSTED",
                                "STOCK_ADJUSTED", "BOOK_DELETED", "PRICE_CHANGED"))
                .jsonPath("$.content[*].actor").value(actors -> assertThat(actors).asInstanceOf(LIST)
                        .hasSize(6).containsOnly(staffUsername)));

        admin.get().uri("/api/audit?authorId={id}", author)
                .exchange()
                .expectBody().jsonPath("$.content[0].actor").isEqualTo(staffUsername);
    }

    private RestTestClient.ResponseSpec post(String path, String json) {
        return staff.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(json).exchange();
    }

    @SuppressWarnings("unchecked")
    private static long id(RestTestClient.ResponseSpec created) {
        Map<String, Object> body = created.expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) body.get("id")).longValue();
    }
}
