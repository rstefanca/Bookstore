package cz.codingmonkey.bookstore.security;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Starts with no configured admin password, in a context (and database) of its own: the first admin gets a
 * generated password, and it is the only admin, which the last-admin rule needs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bookstore.security.initial-admin.username=admin", "bookstore.security.initial-admin.password=",
                "bookstore.security.initial-admin.first-name=Ada",
                "bookstore.security.initial-admin.last-name=Lovelace"})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InitialAdminIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Order(1)
    void createsTheFirstAdminWithAGeneratedPasswordThatIsLoggedOnce(CapturedOutput output) {
        var generated = Pattern.compile("admin account \"admin\" with generated password: (\\S+)")
                .matcher(output.getAll());
        assertThat(generated.find()).as("password logged").isTrue();
        String password = generated.group(1);
        assertThat(password).hasSize(24);
        assertThat(generated.find()).as("logged only once").isFalse();

        RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth("admin", password))
                .build()
                .get().uri("/api/accounts/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.role").isEqualTo("ADMIN")
                .jsonPath("$.firstName").isEqualTo("Ada")
                .jsonPath("$.lastName").isEqualTo("Lovelace");
    }

    @Test
    @Order(2)
    void twoAdminsDeletingEachOtherAtOnceLeaveOne() throws Exception {
        AccountId first = accounts.findByUsername("admin").id();
        AccountId second = accounts.create("second-admin", "Second", "Admin", "a-long-password", Role.ADMIN).id();
        var bothReady = new CyclicBarrier(2);

        List<CompletableFuture<Void>> deletions = new ArrayList<>();
        deletions.add(CompletableFuture.runAsync(() -> deleteWhenReady(bothReady, first, "second-admin")));
        deletions.add(CompletableFuture.runAsync(() -> deleteWhenReady(bothReady, second, "admin")));

        List<Throwable> failures = new ArrayList<>();
        for (var deletion : deletions) {
            try {
                deletion.join();
            } catch (Exception e) {
                failures.add(e.getCause());
            }
        }

        assertThat(failures).singleElement().isInstanceOf(LastAdminException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE role = 'ADMIN'", Integer.class))
                .isEqualTo(1);
    }

    private void deleteWhenReady(CyclicBarrier bothReady, AccountId id, String deletedBy) {
        try {
            bothReady.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        accounts.delete(id, deletedBy);
    }
}
