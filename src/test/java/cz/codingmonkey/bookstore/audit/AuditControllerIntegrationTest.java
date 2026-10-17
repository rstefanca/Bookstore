package cz.codingmonkey.bookstore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Filtering the audit log by subject: one type and id together, nothing else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class AuditControllerIntegrationTest {

    /** Signed in as the test admin. */
    @Autowired
    RestTestClient client;

    @Test
    void filtersBySubject() {
        long author = createAuthor();

        // Audit entries are recorded asynchronously after each change commits.
        await().untilAsserted(() -> client.get().uri("/api/audit?subjectType=AUTHOR&subjectId={id}", author)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[*].action").value(actions -> assertThat(actions).asInstanceOf(LIST)
                        .containsExactly("AUTHOR_CREATED"))
                .jsonPath("$.content[0].subject.type").isEqualTo("AUTHOR")
                .jsonPath("$.content[0].subject.id").isEqualTo(author));

        // The same id as a book is another subject (other tests may have created that book).
        client.get().uri("/api/audit?subjectType=BOOK&subjectId={id}", author)
                .exchange()
                .expectBody().jsonPath("$.content[*].subject.type").value(types -> assertThat(types)
                        .asInstanceOf(LIST).doesNotContain("AUTHOR"));
    }

    @Test
    void needsBothSubjectTypeAndId() {
        client.get().uri("/api/audit?subjectType=BOOK").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/audit?subjectId=1").exchange().expectStatus().isBadRequest();
    }

    @Test
    void rejectsUnknownSubjectTypesAndNonPositiveIds() {
        client.get().uri("/api/audit?subjectType=SPACESHIP&subjectId=1").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/audit?subjectType=BOOK&subjectId=0").exchange().expectStatus().isBadRequest();
    }

    @Test
    void rejectsTheOldFiltersInsteadOfReturningEverything() {
        client.get().uri("/api/audit?bookId=1").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/audit?authorId=1").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/audit?orderId=1").exchange().expectStatus().isBadRequest();
    }

    @SuppressWarnings("unchecked")
    private long createAuthor() {
        Map<String, Object> body = client.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Audited " + UUID.randomUUID() + "\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) body.get("id")).longValue();
    }
}
