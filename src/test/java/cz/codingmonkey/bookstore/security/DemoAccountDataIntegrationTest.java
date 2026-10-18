package cz.codingmonkey.bookstore.security;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestAccounts;
import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Demo staff accounts, in the same (cached) context as the other demo data tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration.class)
class DemoAccountDataIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    AccountRepository accounts;

    @Autowired
    DemoAccountData demoAccounts;

    @Test
    void demoStaffCanSignInAndDoStaffWork() {
        assertStaff("anna", "anna-demo-password", "Anna", "Nováková");
        assertStaff("tomas", "tomas-demo-password", "Tomáš", "Dvořák");
    }

    @Test
    void theInitialAdminIsStillCreated() {
        assertThat(accounts.findByUsernameIgnoreCaseAndDeletedAtIsNull(TestAccounts.ADMIN_USERNAME))
                .hasValueSatisfying(admin -> assertThat(admin.getRole()).isEqualTo(Role.ADMIN));
    }

    @Test
    void runningAgainCreatesNothing() throws Exception {
        long before = accounts.count();

        demoAccounts.run(null);

        assertThat(accounts.count()).isEqualTo(before);
    }

    private void assertStaff(String username, String password, String firstName, String lastName) {
        RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(username, password))
                .build();
        client.get().uri("/api/accounts/me").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.role").isEqualTo("STAFF")
                .jsonPath("$.firstName").isEqualTo(firstName)
                .jsonPath("$.lastName").isEqualTo(lastName);
        client.get().uri("/api/inventory").exchange().expectStatus().isOk();
    }
}
