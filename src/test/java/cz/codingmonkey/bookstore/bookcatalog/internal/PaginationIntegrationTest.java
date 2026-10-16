package cz.codingmonkey.bookstore.bookcatalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Paging behaviour shared by all list endpoints, checked on books (filtered queries) and genres (seeded data).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class PaginationIntegrationTest {

    @Autowired
    RestTestClient client;

    @Autowired
    BookRepository books;

    @Autowired
    AuthorRepository authors;

    @BeforeEach
    void createTwentyFiveBooks() {
        books.deleteAll();
        var author = authors.save(new Author("Prolific Writer")).getId();
        // Titles sort as "Book 01" ... "Book 25".
        IntStream.rangeClosed(1, 25).forEach(i -> books.save(new Book("Book %02d".formatted(i), List.of(author),
                Set.of(), "isbn-page-%02d".formatted(i), 2000, null)));
    }

    @Test
    void returnsTheFirstPageOfTwentyWithMetadataByDefault() {
        client.get().uri("/api/books")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(20)
                .jsonPath("$.content[0].title").isEqualTo("Book 01")
                .jsonPath("$.page.number").isEqualTo(0)
                .jsonPath("$.page.size").isEqualTo(20)
                .jsonPath("$.page.totalElements").isEqualTo(25)
                .jsonPath("$.page.totalPages").isEqualTo(2);
    }

    @Test
    void pagesDontOverlapAndCoverEverything() {
        List<Object> titles = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            client.get().uri("/api/books?page={page}&size=10", page)
                    .exchange()
                    .expectBody()
                    .jsonPath("$.content[*].title").value(pageTitles -> titles.addAll((List<?>) pageTitles));
        }

        assertThat(titles).hasSize(25).doesNotHaveDuplicates().isSortedAccordingTo(
                (a, b) -> ((String) a).compareTo((String) b));
    }

    @Test
    void returnsAnEmptyPageBeyondTheEnd() {
        client.get().uri("/api/books?page=5")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content").isEmpty()
                .jsonPath("$.page.totalElements").isEqualTo(25);
    }

    @Test
    void capsThePageSize() {
        client.get().uri("/api/genres?size=1000")
                .exchange()
                .expectBody().jsonPath("$.page.size").isEqualTo(100);
    }

    @Test
    void appliesFiltersBeforePaging() {
        client.get().uri("/api/books?title=Book 2&size=3")
                .exchange()
                .expectBody()
                .jsonPath("$.content[*].title").value(titles -> assertThat(titles).asInstanceOf(LIST)
                        .containsExactly("Book 20", "Book 21", "Book 22"))
                .jsonPath("$.page.totalElements").isEqualTo(6); // Book 20 ... Book 25
    }

    @Test
    void ignoresClientSortParameter() {
        client.get().uri("/api/books?sort=isbn,desc&size=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.content[0].title").isEqualTo("Book 01");
    }
}
