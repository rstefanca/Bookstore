package cz.codingmonkey.bookstore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cz.codingmonkey.bookstore.TestAccountIds;
import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The filter rules of {@code GET /api/audit}, with entries seeded straight into the repository.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class AuditControllerIntegrationTest {

    /** Signed in as the test admin. */
    @Autowired
    RestTestClient admin;

    @Autowired
    AuditEntryRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void filtersBySubject() {
        long n = System.nanoTime() % 1_000_000 + 1_000_000;
        var now = Instant.now();
        AccountId jane = TestAccountIds.next();
        repository.save(AuditEntry.of("BOOK_CREATED", new AuditSubject("BOOK", n), jane, "Created", now));
        repository.save(AuditEntry.of("PRICE_CHANGED", new AuditSubject("BOOK", n), jane, "Price", now));
        repository.save(AuditEntry.of("AUTHOR_CREATED", new AuditSubject("AUTHOR", n), jane, "Author", now));

        admin.get().uri("/api/audit?subjectType=BOOK&subjectId={n}", n)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.content[*].subject.type").value(types -> assertThat(types).asInstanceOf(LIST)
                        .hasSize(2).containsOnly("BOOK"))
                .jsonPath("$.content[*].subject.id").value(ids -> assertThat(ids).asInstanceOf(LIST)
                        .hasSize(2).containsOnly((int) n))
                .jsonPath("$.content[0].bookId").doesNotExist()
                .jsonPath("$.content[0].authorId").doesNotExist()
                .jsonPath("$.content[0].orderId").doesNotExist();
    }

    @Test
    void auditEntriesShowActorNameAndId() {
        String username = "auditee-" + UUID.randomUUID().toString().substring(0, 8);
        long staff = createStaff(username);
        long n = System.nanoTime() % 1_000_000 + 2_000_000;
        repository.save(AuditEntry.of("BOOK_CREATED", new AuditSubject("BOOK", n), new AccountId(staff), "Created",
                Instant.now()));
        repository.save(AuditEntry.of("BOOK_DELETED", new AuditSubject("BOOK", n), null, "Deleted",
                Instant.now().plusSeconds(1)));

        admin.get().uri("/api/audit?subjectType=BOOK&subjectId={n}", n)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.content[1].actor").isEqualTo(username)
                .jsonPath("$.content[1].actorId").isEqualTo(staff)
                .jsonPath("$.content[0].actor").isEmpty()
                .jsonPath("$.content[0].actorId").isEmpty();
    }

    @Test
    void auditEntryOfADeletedStaffMemberShowsDeletedUser() {
        long staff = createStaff("leaver-" + UUID.randomUUID().toString().substring(0, 8));
        long n = System.nanoTime() % 1_000_000 + 3_000_000;
        repository.save(AuditEntry.of("BOOK_CREATED", new AuditSubject("BOOK", n), new AccountId(staff), "Created",
                Instant.now()));

        jdbc.update("UPDATE account SET deleted_at = now(), username = '~deleted-' || id WHERE id = ?", staff);

        admin.get().uri("/api/audit?subjectType=BOOK&subjectId={n}", n)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].actor").isEqualTo("(deleted user)")
                .jsonPath("$.content[0].actorId").isEqualTo(staff);
    }

    @SuppressWarnings("unchecked")
    private long createStaff(String username) {
        Map<String, Object> created = admin.post().uri("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Sam", "lastName": "Staff",
                         "password": "a-long-password", "role": "STAFF"}
                        """.formatted(username))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) created.get("id")).longValue();
    }

    @Test
    void unknownTypeGivesAnEmptyPage() {
        admin.get().uri("/api/audit?subjectType=SHIPMENT&subjectId=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.content").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "subjectType=BOOK",
            "subjectId=1",
            "subjectType=book&subjectId=1",
            "subjectType=B-1&subjectId=1",
            "subjectType=BOOK&subjectId=0",
            "subjectType=BOOK&subjectId=abc"})
    void invalidFilters(String query) {
        admin.get().uri("/api/audit?" + query).exchange().expectStatus().isBadRequest();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bookId=1", "authorId=1", "orderId=", "subjectType=BOOK&subjectId=1&bookId=1"})
    void oldFiltersAreRejected(String query) {
        admin.get().uri("/api/audit?" + query).exchange().expectStatus().isBadRequest();
    }
}
