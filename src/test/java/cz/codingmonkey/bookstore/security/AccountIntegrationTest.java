package cz.codingmonkey.bookstore.security;

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
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Account management over HTTP. The injected client is signed in as the test admin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class AccountIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    RestTestClient admin;

    String username;

    @BeforeEach
    void uniqueUsername() {
        username = "acct-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createdAccountsCanSignIn() {
        create(username, "a-long-password", "STAFF")
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectBody()
                .jsonPath("$.username").isEqualTo(username)
                .jsonPath("$.firstName").isEqualTo("Jane")
                .jsonPath("$.lastName").isEqualTo("Doe")
                .jsonPath("$.role").isEqualTo("STAFF")
                .jsonPath("$.password").doesNotExist()
                .jsonPath("$.passwordHash").doesNotExist();

        signedInAs(username, "a-long-password").get().uri("/api/accounts/me").exchange().expectStatus().isOk();
    }

    @Test
    void validatesNewAccounts() {
        create(username, "too-short", "STAFF").expectStatus().isBadRequest();
        create(username, "x".repeat(73), "STAFF").expectStatus().isBadRequest(); // BCrypt's 72-byte limit
        create(username, "ä".repeat(37), "STAFF").expectStatus().isBadRequest(); // 37 characters, 74 bytes
        create("no spaces allowed", "a-long-password", "STAFF").expectStatus().isBadRequest();
        create("ab", "a-long-password", "STAFF").expectStatus().isBadRequest();
        create(username, "a-long-password", "OWNER").expectStatus().isBadRequest();
    }

    @Test
    void requiresFirstAndLastName() {
        String noFirstName = """
                {"username": "%s", "lastName": "Doe", "password": "a-long-password", "role": "STAFF"}
                """.formatted(username);
        String blankLastName = """
                {"username": "%s", "firstName": "Jane", "lastName": "  ",
                 "password": "a-long-password", "role": "STAFF"}
                """.formatted(username);
        String tooLong = """
                {"username": "%s", "firstName": "%s", "lastName": "Doe",
                 "password": "a-long-password", "role": "STAFF"}
                """.formatted(username, "x".repeat(101));

        createJson(noFirstName).expectStatus().isBadRequest();
        createJson(blankLastName).expectStatus().isBadRequest();
        createJson(tooLong).expectStatus().isBadRequest();
    }

    @Test
    void stripsNamesAndAcceptsAnyScript() {
        createJson("""
                {"username": "%s", "firstName": "  Zoë-Ann ", "lastName": "O'Brien Šťastná",
                 "password": "a-long-password", "role": "STAFF"}
                """.formatted(username))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.firstName").isEqualTo("Zoë-Ann")
                .jsonPath("$.lastName").isEqualTo("O'Brien Šťastná");
    }

    @Test
    void adminsCanChangeNames() {
        long id = createdId(username, "a-long-password", "STAFF");

        admin.put().uri("/api/accounts/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"firstName\": \"Janet\", \"lastName\": \"Smith\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.firstName").isEqualTo("Janet")
                .jsonPath("$.lastName").isEqualTo("Smith");
        admin.put().uri("/api/accounts/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"firstName\": \"\", \"lastName\": \"Smith\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void everyoneCanChangeTheirOwnName() {
        createdId(username, "a-long-password", "STAFF");
        RestTestClient self = signedInAs(username, "a-long-password");

        self.put().uri("/api/accounts/me").contentType(MediaType.APPLICATION_JSON)
                .body("{\"firstName\": \"Jo\", \"lastName\": \"Dee\"}")
                .exchange().expectStatus().isOk();

        self.get().uri("/api/accounts/me")
                .exchange()
                .expectBody()
                .jsonPath("$.firstName").isEqualTo("Jo")
                .jsonPath("$.lastName").isEqualTo("Dee");
    }

    @Test
    void staffCantChangeOtherAccountsNames() {
        long other = createdId(username, "a-long-password", "STAFF");
        createdId(username + "-2", "a-long-password", "STAFF");

        signedInAs(username + "-2", "a-long-password").put().uri("/api/accounts/{id}", other)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"firstName\": \"Mallory\", \"lastName\": \"X\"}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void accountsWithoutNamesShowNone() {
        // The test admin is created at startup without configured names, like accounts that predate names.
        admin.get().uri("/api/accounts/me")
                .exchange()
                .expectBody()
                .jsonPath("$.firstName").isEmpty()
                .jsonPath("$.lastName").isEmpty();
    }

    @Test
    void usernamesAreUniqueIgnoringCase() {
        create(username, "a-long-password", "STAFF").expectStatus().isCreated();

        create(username.toUpperCase(), "a-long-password", "STAFF").expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void adminsCanResetPasswords() {
        long id = createdId(username, "the-old-password", "STAFF");

        admin.put().uri("/api/accounts/{id}/password", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"password\": \"the-new-password\"}")
                .exchange().expectStatus().isNoContent();

        signedInAs(username, "the-old-password").get().uri("/api/accounts/me").exchange()
                .expectStatus().isUnauthorized();
        signedInAs(username, "the-new-password").get().uri("/api/accounts/me").exchange().expectStatus().isOk();
    }

    @Test
    void everyoneCanChangeTheirOwnPasswordWithTheCurrentOne() {
        createdId(username, "the-old-password", "STAFF");
        RestTestClient self = signedInAs(username, "the-old-password");

        changeOwnPassword(self, "not-the-password", "the-new-password").expectStatus().isBadRequest();
        changeOwnPassword(self, "the-old-password", "short").expectStatus().isBadRequest();
        changeOwnPassword(self, "the-old-password", "the-new-password").expectStatus().isNoContent();

        signedInAs(username, "the-new-password").get().uri("/api/accounts/me").exchange().expectStatus().isOk();
    }

    @Test
    void deletedAccountsCanNoLongerSignIn() {
        long id = createdId(username, "a-long-password", "STAFF");

        admin.delete().uri("/api/accounts/{id}", id).exchange().expectStatus().isNoContent();

        signedInAs(username, "a-long-password").get().uri("/api/accounts/me").exchange()
                .expectStatus().isUnauthorized();
        admin.delete().uri("/api/accounts/{id}", id).exchange().expectStatus().isNotFound();
    }

    @Test
    void adminsCantDeleteThemselves() {
        long otherAdmin = createdId(username, "a-long-password", "ADMIN");

        signedInAs(username, "a-long-password").delete().uri("/api/accounts/{id}", otherAdmin)
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    private RestTestClient.ResponseSpec create(String name, String password, String role) {
        return createJson("""
                {"username": "%s", "firstName": "Jane", "lastName": "Doe", "password": "%s", "role": "%s"}
                """.formatted(name, password, role));
    }

    private RestTestClient.ResponseSpec createJson(String json) {
        return admin.post().uri("/api/accounts").contentType(MediaType.APPLICATION_JSON).body(json).exchange();
    }

    private long createdId(String name, String password, String role) {
        return ((Number) create(name, password, role).expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id")).longValue();
    }

    private RestTestClient.ResponseSpec changeOwnPassword(RestTestClient client, String current, String next) {
        return client.put().uri("/api/accounts/me/password").contentType(MediaType.APPLICATION_JSON)
                .body("{\"currentPassword\": \"%s\", \"newPassword\": \"%s\"}".formatted(current, next))
                .exchange();
    }

    private RestTestClient signedInAs(String name, String password) {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(name, password))
                .build();
    }
}
