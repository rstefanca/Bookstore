package cz.codingmonkey.bookstore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

    @Test
    void filtersBySubject() {
        long n = System.nanoTime() % 1_000_000 + 1_000_000;
        var now = Instant.now();
        repository.save(AuditEntry.of("BOOK_CREATED", new AuditSubject("BOOK", n), "jane", "Created", now));
        repository.save(AuditEntry.of("PRICE_CHANGED", new AuditSubject("BOOK", n), "jane", "Price", now));
        repository.save(AuditEntry.of("AUTHOR_CREATED", new AuditSubject("AUTHOR", n), "jane", "Author", now));

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
